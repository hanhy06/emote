package io.github.hanhy06.emote.skin.account;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.config.Config;
import io.github.hanhy06.emote.skin.PlayerSkinBaker;
import io.github.hanhy06.emote.skin.PlayerSkinProvider;
import io.github.hanhy06.emote.skin.mineskin.MineSkinCache;
import io.github.hanhy06.emote.skin.model.PlayerSkinPreparation;
import io.github.hanhy06.emote.skin.model.PlayerSkinRegion;
import io.github.hanhy06.emote.skin.model.PlayerSkinSource;
import io.github.hanhy06.emote.skin.model.PreparedPlayerSkin;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public final class AccountSkinProvider implements PlayerSkinProvider {
    private final MinecraftAccountManager accounts;
    private final PlayerSkinBaker baker;
    private final MinecraftSkinClient client;
    private final MineSkinCache cache;
    private final AccountBakeQueue queue;
    private final Map<String, Bake> bakes = new HashMap<>();
    private final Map<String, CompletableFuture<String>> uploads = new HashMap<>();
    private final Map<String, Long> failures = new LinkedHashMap<>();
    private ExecutorService executor;
    private Listener listener = new Listener() {};
    private long generation;

    public AccountSkinProvider(MinecraftAccountManager accounts, PlayerSkinBaker baker, MinecraftSkinClient client, MineSkinCache cache, AccountBakeQueue queue) {
        this.accounts = accounts;
        this.baker = baker;
        this.client = client;
        this.cache = cache;
        this.queue = queue;
    }

    @Override public void onConfigReload(Config config) {
        // Shared cache maintenance is configured by MineSkinProvider, even while accounts are selected.
    }

    @Override public synchronized void setListener(Listener listener) {
        this.listener = Objects.requireNonNull(listener);
    }

    @Override public synchronized PlayerSkinPreparation prepare(PlayerSkinSource source, Set<PlayerSkinRegion> requiredRegions) {
        Map<PlayerSkinRegion, String> stored = this.cache.load(source.textureHash(), source.slimModel());
        Map<PlayerSkinRegion, String> ready = new HashMap<>();
        for (PlayerSkinRegion region : requiredRegions) {
            if (stored.containsKey(region)) ready.put(region, stored.get(region));
        }
        int progress = requiredRegions.isEmpty() ? 100 : ready.size() * 100 / requiredRegions.size();
        PreparedPlayerSkin skin = ready.isEmpty() ? null : new PreparedPlayerSkin(ready);
        if (progress == 100) return new PlayerSkinPreparation(skin, PlayerSkinPreparation.State.READY, 100);
        String key = source.textureHash() + ":" + source.slimModel();
        long now = System.currentTimeMillis();
        this.failures.values().removeIf(until -> until <= now);
        if (this.failures.containsKey(key)) return new PlayerSkinPreparation(skin, PlayerSkinPreparation.State.FAILED, progress);
        Bake bake = this.bakes.get(key);
        if (bake == null) {
            if (this.accounts.storageError() != null || this.accounts.accounts().stream().noneMatch(account -> !account.needsLogin())) {
                return new PlayerSkinPreparation(skin, PlayerSkinPreparation.State.UNAVAILABLE, progress);
            }
            bake = new Bake(source, this.generation);
            this.bakes.put(key, bake);
            bake.regions.addAll(requiredRegions);
            bake.subscribers.add(source.playerUuid());
            if (this.executor == null) this.executor = Executors.newVirtualThreadPerTaskExecutor();
            Bake newBake = bake;
            this.executor.execute(() -> bake(key, newBake));
        } else {
            bake.regions.addAll(requiredRegions);
            bake.subscribers.add(source.playerUuid());
        }
        return new PlayerSkinPreparation(skin, PlayerSkinPreparation.State.PREPARING, progress);
    }

    private void bake(String key, Bake bake) {
        try {
            PlayerSkinSource source = bake.source;
            var prepared = this.baker.prepare(this.client.downloadSkin(source.textureUrl()), source.slimModel());
            while (true) {
                Set<PlayerSkinRegion> missing;
                synchronized (this) {
                    if (bake.generation != this.generation) return;
                    missing = new LinkedHashSet<>(bake.regions);
                    missing.removeAll(this.cache.load(source.textureHash(), source.slimModel()).keySet());
                    if (missing.isEmpty()) {
                        this.bakes.remove(key, bake);
                        for (UUID subscriber : bake.subscribers) this.listener.onReady(subscriber);
                        return;
                    }
                }
                List<CompletableFuture<Void>> pending = new ArrayList<>();
                for (PlayerSkinRegion region : missing) {
                    byte[] png = this.baker.bake(prepared, region.skinPart(), region.skinSegment());
                    CompletableFuture<String> upload = texture(png, source.slimModel(), bake.generation);
                    pending.add(upload.thenAccept(url -> {
                        synchronized (this) {
                            if (bake.generation == this.generation) {
                                this.cache.save(source.textureHash(), source.slimModel(), Map.of(region, url));
                                if (!url.equals(this.cache.load(source.textureHash(), source.slimModel()).get(region))) {
                                    throw new CompletionException(new IOException("Could not save baked skin texture"));
                                }
                            }
                        }
                    }));
                }
                CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).get();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException | ExecutionException | RuntimeException exception) {
            synchronized (this) {
                if (bake.generation != this.generation) return;
                this.bakes.remove(key, bake);
                if (this.failures.size() >= 1024) this.failures.remove(this.failures.keySet().iterator().next());
                this.failures.put(key, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5));
                for (UUID subscriber : bake.subscribers) this.listener.onFailed(subscriber);
            }
            EmoteMod.LOGGER.warn("Account skin bake failed for {}; retry later or check /emote account", bake.source.playerUuid());
        }
    }

    private synchronized CompletableFuture<String> texture(byte[] png, boolean slim, long expectedGeneration) {
        if (expectedGeneration != this.generation) return CompletableFuture.failedFuture(new CancellationException());
        String key = MineSkinCache.createContentKey(png, slim);
        String cached = this.cache.loadContent(key);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        CompletableFuture<String> existing = this.uploads.get(key);
        if (existing != null) return existing;
        CompletableFuture<String> result = new CompletableFuture<>();
        this.uploads.put(key, result);
        this.queue.submit(png, slim).whenCompleteAsync((url, error) -> {
            synchronized (this) {
                if (expectedGeneration != this.generation) {
                    result.cancel(false);
                    return;
                }
                if (error == null) this.cache.saveContent(key, url);
                this.uploads.remove(key, result);
                if (error == null) result.complete(url);
                else result.completeExceptionally(error);
            }
        });
        return result;
    }

    @Override public void cancelPendingBakes() {
        List<CompletableFuture<String>> pending;
        synchronized (this) {
            this.generation++;
            this.bakes.clear();
            this.failures.clear();
            pending = List.copyOf(this.uploads.values());
            this.uploads.clear();
            if (this.executor != null) this.executor.shutdownNow();
            this.executor = null;
        }
        this.queue.cancelAll();
        pending.forEach(future -> future.cancel(false));
    }

    private static final class Bake {
        final PlayerSkinSource source;
        final long generation;
        final Set<PlayerSkinRegion> regions = new LinkedHashSet<>();
        final Set<UUID> subscribers = new LinkedHashSet<>();

        Bake(PlayerSkinSource source, long generation) {
            this.source = source;
            this.generation = generation;
        }
    }
}
