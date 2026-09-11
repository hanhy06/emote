package io.github.hanhy06.emote.skin;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.config.Config;
import io.github.hanhy06.emote.skin.account.AccountBakeQueue;
import io.github.hanhy06.emote.skin.account.MinecraftAccountManager;
import io.github.hanhy06.emote.skin.account.MinecraftSkinClient;
import io.github.hanhy06.emote.skin.mineskin.MineSkinCache;
import io.github.hanhy06.emote.skin.model.PlayerSkinPreparation;
import io.github.hanhy06.emote.skin.model.PlayerSkinRegion;
import io.github.hanhy06.emote.skin.model.PlayerSkinSource;
import io.github.hanhy06.emote.skin.model.PreparedPlayerSkin;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public final class SkinBakeCoordinator implements PlayerSkinProvider {
    private static final long FAILED_BAKE_RETRY_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private static final long CACHE_CLEANUP_INTERVAL_MILLIS = TimeUnit.DAYS.toMillis(1);

    private final MinecraftAccountManager accounts;
    private final PlayerSkinBaker baker;
    private final MinecraftSkinClient skinClient;
    private final MineSkinCache cache;
    private final AccountBakeQueue accountUploads;
    private final FallbackUploader fallbackUploader;
    private final Map<SkinKey, Bake> bakes = new HashMap<>();
    private final Map<String, CompletableFuture<String>> uploads = new HashMap<>();
    private final Map<SkinKey, Long> failures = new LinkedHashMap<>();

    private ExecutorService executor;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> cacheCleanup;
    private Listener listener = new Listener() {};
    private long generation;

    public SkinBakeCoordinator(
        MinecraftAccountManager accounts,
        PlayerSkinBaker baker,
        MinecraftSkinClient skinClient,
        MineSkinCache cache,
        AccountBakeQueue accountUploads,
        FallbackUploader fallbackUploader
    ) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.baker = Objects.requireNonNull(baker, "baker");
        this.skinClient = Objects.requireNonNull(skinClient, "skinClient");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.accountUploads = Objects.requireNonNull(accountUploads, "accountUploads");
        this.fallbackUploader = Objects.requireNonNull(fallbackUploader, "fallbackUploader");
    }

    @Override
    public synchronized PlayerSkinPreparation prepare(PlayerSkinSource source, Set<PlayerSkinRegion> requiredRegions) {
        Map<PlayerSkinRegion, String> ready = loadReady(source, requiredRegions);
        int progress = requiredRegions.isEmpty() ? 100 : ready.size() * 100 / requiredRegions.size();
        PreparedPlayerSkin skin = ready.isEmpty() ? null : new PreparedPlayerSkin(ready);
        if (progress == 100) {
            return new PlayerSkinPreparation(skin, PlayerSkinPreparation.State.READY, 100);
        }

        SkinKey key = new SkinKey(source.textureHash(), source.slimModel());
        removeExpiredFailures();
        if (this.failures.containsKey(key)) {
            return new PlayerSkinPreparation(skin, PlayerSkinPreparation.State.FAILED, progress);
        }
        if (!hasUploadProvider()) {
            return new PlayerSkinPreparation(skin, PlayerSkinPreparation.State.UNAVAILABLE, progress);
        }

        Bake bake = this.bakes.get(key);
        boolean created = false;
        if (bake == null) {
            bake = new Bake(source, this.generation);
            this.bakes.put(key, bake);
            created = true;
        }
        bake.requiredRegions.addAll(requiredRegions);
        bake.subscribers.add(source.playerUuid());
        if (created) {
            ensureExecutor();
            Bake scheduled = bake;
            this.executor.execute(() -> bake(key, scheduled));
        }
        return new PlayerSkinPreparation(skin, PlayerSkinPreparation.State.PREPARING, progress);
    }

    @Override
    public synchronized void onConfigReload(Config config) {
        this.fallbackUploader.configure(config);
        if (!hasUploadProvider()) {
            EmoteMod.LOGGER.warn("No bake accounts or MineSkin API key configured; only cached skin textures are available");
        }

        if (this.cacheCleanup != null) {
            this.cacheCleanup.cancel(false);
        }
        ensureScheduler();
        long expectedGeneration = this.generation;
        this.cacheCleanup = this.scheduler.schedule(
            () -> cleanupCache(config, expectedGeneration),
            0L,
            TimeUnit.MILLISECONDS
        );
    }

    @Override
    public synchronized void setListener(Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public void cancelPendingBakes() {
        List<CompletableFuture<String>> pendingUploads;
        synchronized (this) {
            this.generation++;
            this.bakes.clear();
            this.failures.clear();
            pendingUploads = List.copyOf(this.uploads.values());
            this.uploads.clear();
            if (this.executor != null) {
                this.executor.shutdownNow();
                this.executor = null;
            }
            if (this.scheduler != null) {
                this.scheduler.shutdownNow();
                this.scheduler = null;
            }
            this.cacheCleanup = null;
        }
        this.accountUploads.cancelAll();
        pendingUploads.forEach(future -> future.cancel(false));
        this.cache.clearMemory();
    }

    @Override
    public synchronized SkinProcessingStats processingStats() {
        removeExpiredFailures();
        int active = 0;
        int queued = 0;
        for (Bake bake : this.bakes.values()) {
            if (bake.running) {
                active++;
            } else {
                queued++;
            }
        }
        String provider = hasUsableAccount() ? "Account" : this.fallbackUploader.available() ? "MineSkin" : "Unavailable";
        return new SkinProcessingStats(provider, active, queued, this.failures.size());
    }

    private void bake(SkinKey key, Bake bake) {
        try {
            synchronized (this) {
                if (!isCurrent(key, bake)) {
                    return;
                }
                bake.running = true;
            }
            PlayerSkinSource source = bake.source;
            BufferedImage sourceImage = this.skinClient.downloadSkin(source.textureUrl());
            PlayerSkinBaker.PreparedSkin prepared = this.baker.prepare(sourceImage, source.slimModel());

            while (true) {
                Set<PlayerSkinRegion> missing;
                synchronized (this) {
                    if (!isCurrent(key, bake)) {
                        return;
                    }
                    missing = new LinkedHashSet<>(bake.requiredRegions);
                    missing.removeAll(this.cache.load(source.textureHash(), source.slimModel()).keySet());
                }
                if (missing.isEmpty()) {
                    complete(key, bake);
                    return;
                }

                for (PlayerSkinRegion region : missing) {
                    byte[] png = this.baker.bake(prepared, region.skinPart(), region.skinSegment());
                    String url = texture(png, source.slimModel(), bake.generation).get();
                    synchronized (this) {
                        if (!isCurrent(key, bake)) {
                            return;
                        }
                        this.cache.save(source.textureHash(), source.slimModel(), Map.of(region, url));
                        if (!url.equals(this.cache.load(source.textureHash(), source.slimModel()).get(region))) {
                            throw new IOException("Could not save baked skin texture");
                        }
                    }
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException | ExecutionException | RuntimeException exception) {
            fail(key, bake, exception);
        }
    }

    private synchronized CompletableFuture<String> texture(byte[] png, boolean slimModel, long expectedGeneration) {
        if (expectedGeneration != this.generation) {
            return CompletableFuture.failedFuture(new CancellationException());
        }
        String contentKey = MineSkinCache.createContentKey(png, slimModel);
        String cached = this.cache.loadContent(contentKey);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        CompletableFuture<String> existing = this.uploads.get(contentKey);
        if (existing != null) {
            return existing;
        }

        CompletableFuture<String> result = new CompletableFuture<>();
        this.uploads.put(contentKey, result);
        ensureExecutor();
        this.executor.execute(() -> upload(contentKey, png, slimModel, expectedGeneration, result));
        return result;
    }

    private void upload(
        String contentKey,
        byte[] png,
        boolean slimModel,
        long expectedGeneration,
        CompletableFuture<String> result
    ) {
        try {
            String url = uploadWithSelectedProvider(png, slimModel);
            synchronized (this) {
                if (expectedGeneration != this.generation) {
                    result.cancel(false);
                    return;
                }
                this.cache.saveContent(contentKey, url);
                this.uploads.remove(contentKey, result);
                result.complete(url);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            completeUploadExceptionally(contentKey, expectedGeneration, result, exception);
        } catch (IOException | ExecutionException | RuntimeException exception) {
            completeUploadExceptionally(contentKey, expectedGeneration, result, exception);
        }
    }

    private String uploadWithSelectedProvider(byte[] png, boolean slimModel)
        throws IOException, InterruptedException, ExecutionException {
        if (this.accounts.storageError() != null) {
            throw new IOException(this.accounts.storageError());
        }
        if (hasUsableAccount()) {
            try {
                return this.accountUploads.submit(png, slimModel).get();
            } catch (ExecutionException exception) {
                if (!this.accounts.hasAccounts() && this.fallbackUploader.available()) {
                    return this.fallbackUploader.upload(png, slimModel);
                }
                throw exception;
            }
        }
        if (this.accounts.hasAccounts()) {
            throw new IOException("No usable bake account; run /emote account login");
        }
        if (this.fallbackUploader.available()) {
            return this.fallbackUploader.upload(png, slimModel);
        }
        throw new IOException("No skin upload provider is available");
    }

    private synchronized void completeUploadExceptionally(
        String contentKey,
        long expectedGeneration,
        CompletableFuture<String> result,
        Throwable exception
    ) {
        if (expectedGeneration != this.generation) {
            result.cancel(false);
            return;
        }
        this.uploads.remove(contentKey, result);
        result.completeExceptionally(exception);
    }

    private void complete(SkinKey key, Bake bake) {
        Set<UUID> subscribers;
        Listener currentListener;
        synchronized (this) {
            if (!this.bakes.remove(key, bake)) {
                return;
            }
            subscribers = Set.copyOf(bake.subscribers);
            currentListener = this.listener;
        }
        subscribers.forEach(currentListener::onReady);
    }

    private void fail(SkinKey key, Bake bake, Throwable exception) {
        Set<UUID> subscribers;
        Listener currentListener;
        synchronized (this) {
            if (!isCurrent(key, bake)) {
                return;
            }
            this.bakes.remove(key, bake);
            if (this.failures.size() >= 1_024) {
                this.failures.remove(this.failures.keySet().iterator().next());
            }
            this.failures.put(key, System.currentTimeMillis() + FAILED_BAKE_RETRY_MILLIS);
            subscribers = Set.copyOf(bake.subscribers);
            currentListener = this.listener;
        }
        subscribers.forEach(currentListener::onFailed);
        EmoteMod.LOGGER.warn("Skin bake failed for {}; retry later or check /emote account", bake.source.playerUuid(), exception);
    }

    private void cleanupCache(Config config, long expectedGeneration) {
        synchronized (this) {
            if (expectedGeneration != this.generation) {
                return;
            }
        }
        this.fallbackUploader.cleanupCache(config.mineSkinCacheRetentionDays(), config.mineSkinCacheMaxMiB());
        synchronized (this) {
            if (expectedGeneration == this.generation && this.scheduler != null) {
                this.cacheCleanup = this.scheduler.schedule(
                    () -> cleanupCache(config, expectedGeneration),
                    CACHE_CLEANUP_INTERVAL_MILLIS,
                    TimeUnit.MILLISECONDS
                );
            }
        }
    }

    private Map<PlayerSkinRegion, String> loadReady(PlayerSkinSource source, Set<PlayerSkinRegion> requiredRegions) {
        Map<PlayerSkinRegion, String> stored = this.cache.load(source.textureHash(), source.slimModel());
        Map<PlayerSkinRegion, String> ready = new HashMap<>();
        for (PlayerSkinRegion region : requiredRegions) {
            if (stored.containsKey(region)) {
                ready.put(region, stored.get(region));
            }
        }
        return Map.copyOf(ready);
    }

    private boolean isCurrent(SkinKey key, Bake bake) {
        return bake.generation == this.generation && this.bakes.get(key) == bake;
    }

    private boolean hasUploadProvider() {
        return this.accounts.storageError() == null
            && (hasUsableAccount() || (!this.accounts.hasAccounts() && this.fallbackUploader.available()));
    }

    private boolean hasUsableAccount() {
        return this.accounts.accounts().stream().anyMatch(account -> !account.needsLogin());
    }

    private void removeExpiredFailures() {
        long now = System.currentTimeMillis();
        this.failures.values().removeIf(until -> until <= now);
    }

    private void ensureExecutor() {
        if (this.executor == null) {
            this.executor = Executors.newVirtualThreadPerTaskExecutor();
        }
    }

    private void ensureScheduler() {
        if (this.scheduler == null) {
            this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "emote-skin-cache-cleanup");
                thread.setDaemon(true);
                return thread;
            });
        }
    }

    private record SkinKey(String textureHash, boolean slimModel) {
    }

    private static final class Bake {
        private final PlayerSkinSource source;
        private final long generation;
        private final Set<PlayerSkinRegion> requiredRegions = new LinkedHashSet<>();
        private final Set<UUID> subscribers = new LinkedHashSet<>();
        private boolean running;

        private Bake(PlayerSkinSource source, long generation) {
            this.source = source;
            this.generation = generation;
        }
    }

    public interface FallbackUploader {
        void configure(Config config);

        boolean available();

        String upload(byte[] png, boolean slimModel) throws IOException, InterruptedException;

        void cleanupCache(int retentionDays, int maximumMiB);
    }
}
