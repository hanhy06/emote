package io.github.hanhy06.emote.skin;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.config.Config;
import io.github.hanhy06.emote.config.ConfigListener;
import io.github.hanhy06.emote.skin.account.AccountBakeQueue;
import io.github.hanhy06.emote.skin.account.MinecraftAccountManager;
import io.github.hanhy06.emote.skin.account.MinecraftSkinClient;
import io.github.hanhy06.emote.skin.mineskin.MineSkinProvider;
import io.github.hanhy06.emote.skin.model.PlayerSkinPreparation;
import io.github.hanhy06.emote.skin.model.PlayerSkinRegion;
import io.github.hanhy06.emote.skin.model.PlayerSkinSource;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import java.util.*;
import java.util.concurrent.*;

public final class SkinBakeCoordinator implements ConfigListener {
    private static final long FAILED_BAKE_RETRY_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private static final long CACHE_CLEANUP_INTERVAL_MILLIS = TimeUnit.DAYS.toMillis(1);
    private static final long MEBIBYTE_BYTES = 1_024L * 1_024L;

    private final MinecraftAccountManager accounts;
    private final PlayerSkinBaker baker;
    private final MinecraftSkinClient skinClient;
    private final SkinCache cache;
    private final AccountBakeQueue accountUploads;
    private final MineSkinProvider mineSkin;
    private final Map<PlayerSkinSource.Key, Bake> bakes = new HashMap<>();
    private final Map<String, CompletableFuture<String>> uploads = new HashMap<>();
    private final Map<PlayerSkinSource.Key, Long> failures = new LinkedHashMap<>();

    private ExecutorService executor;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> cacheCleanup;
    private Listener listener = new Listener() {};
    private long generation;
    private boolean stopping;
    public SkinBakeCoordinator(
        MinecraftAccountManager accounts,
        PlayerSkinBaker baker,
        MinecraftSkinClient skinClient,
        SkinCache cache,
        AccountBakeQueue accountUploads,
        MineSkinProvider mineSkin
    ) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.baker = Objects.requireNonNull(baker, "baker");
        this.skinClient = Objects.requireNonNull(skinClient, "skinClient");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.accountUploads = Objects.requireNonNull(accountUploads, "accountUploads");
        this.mineSkin = Objects.requireNonNull(mineSkin, "mineSkin");
    }

    public CompletableFuture<PlayerSkinSource> embed(PlayerSkinSource source) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return PlayerSkinSource.fromPng(this.baker.normalize(this.skinClient.downloadSkin(source.textureUrl()), source.slimModel()));
            } catch (IOException | InterruptedException exception) {
                if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new CompletionException(exception);
            }
        }, net.minecraft.util.Util.nonCriticalIoPool());
    }

    public synchronized PlayerSkinPreparation prepare(PlayerSkinSource source, Set<PlayerSkinRegion> requiredRegions) {
        if (this.stopping) return new PlayerSkinPreparation(null, PlayerSkinPreparation.State.UNAVAILABLE, 0);
        Map<PlayerSkinRegion, String> stored = this.cache.peek(source.textureHash(), source.slimModel());
        Map<PlayerSkinRegion, String> ready = new HashMap<>();
        if (stored != null) {
            for (PlayerSkinRegion region : requiredRegions) {
                String url = stored.get(region);
                if (url != null) ready.put(region, url);
            }
            if (this.cache.markUsed(source.textureHash(), source.slimModel())) {
                long cacheEpoch = this.cache.epoch();
                ensureExecutor();
                this.executor.execute(() -> this.cache.touch(source.textureHash(), source.slimModel(), cacheEpoch));
            }
        }
        int progress = requiredRegions.isEmpty() ? 100 : ready.size() * 100 / requiredRegions.size();
        Map<PlayerSkinRegion, String> skin = ready.isEmpty() ? null : Map.copyOf(ready);
        if (progress == 100) {
            return new PlayerSkinPreparation(skin, PlayerSkinPreparation.State.READY, 100);
        }

        PlayerSkinSource.Key key = new PlayerSkinSource.Key(source.textureHash(), source.slimModel());
        removeExpiredFailures();
        if (this.failures.containsKey(key)) {
            return new PlayerSkinPreparation(skin, PlayerSkinPreparation.State.FAILED, progress);
        }
        if (stored != null && !hasUploadProvider()) {
            return new PlayerSkinPreparation(skin, PlayerSkinPreparation.State.UNAVAILABLE, progress);
        }

        Bake bake = this.bakes.get(key);
        boolean created = false;
        if (bake == null) {
            bake = new Bake(source, this.generation, this.cache.epoch());
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
        this.stopping = false;
        this.mineSkin.configure(config);
        if (!hasUploadProvider()) {
            EmoteMod.LOGGER.error("No bake accounts or MineSkin API key configured; only cached skin textures are available");
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

    public synchronized void setListener(Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public void cancelPendingBakes() {
        List<CompletableFuture<String>> pendingUploads;
        synchronized (this) {
            this.generation++;
            this.stopping = true;
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
            this.cache.clearMemory();
        }
        this.accountUploads.cancelAll();
        pendingUploads.forEach(future -> future.cancel(false));
    }

    synchronized boolean isCurrent(long expectedGeneration) { return !this.stopping && expectedGeneration == this.generation; }

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
        String provider = hasUsableAccount() ? "Account" : this.mineSkin.available() ? "MineSkin" : "Unavailable";
        return new SkinProcessingStats(provider, active, queued, this.failures.size());
    }

    private void bake(PlayerSkinSource.Key key, Bake bake) {
        try {
            synchronized (this) {
                if (!isCurrent(key, bake)) {
                    return;
                }
                bake.running = true;
            }
            PlayerSkinSource source = bake.source;
            Map<PlayerSkinRegion, String> ready = this.cache.load(source.textureHash(), source.slimModel(), bake.cacheEpoch);
            PlayerSkinBaker.PreparedSkin prepared = null;

            while (true) {
                Set<PlayerSkinRegion> missing;
                Set<UUID> completedSubscribers = null;
                Listener completionListener = null;
                boolean unavailable = false;
                synchronized (this) {
                    if (!isCurrent(key, bake)) {
                        return;
                    }
                    missing = new LinkedHashSet<>(bake.requiredRegions);
                    missing.removeAll(ready.keySet());
                    unavailable = !missing.isEmpty() && !hasUploadProvider();
                    if (missing.isEmpty() || unavailable) {
                        this.bakes.remove(key, bake);
                        completedSubscribers = Set.copyOf(bake.subscribers);
                        completionListener = this.listener;
                    }
                }
                if (completedSubscribers != null) {
                    if (!isCurrent(bake.generation)) return;
                    for (UUID subscriber : completedSubscribers) {
                        if (unavailable) completionListener.onUnavailable(subscriber, bake.generation);
                        else completionListener.onReady(subscriber, bake.generation);
                    }
                    return;
                }

                if (prepared == null) {
                    BufferedImage sourceImage = source.png() == null ? this.skinClient.downloadSkin(source.textureUrl())
                        : ImageIO.read(new ByteArrayInputStream(source.png()));
                    prepared = this.baker.prepare(sourceImage, source.slimModel(), source.override());
                }
                for (PlayerSkinRegion region : missing) {
                    byte[] png = this.baker.bake(prepared, region);
                    String url = texture(png, source.slimModel(), bake.generation, bake.cacheEpoch).get();
                    synchronized (this) { if (!isCurrent(key, bake)) return; }
                    if (!this.cache.save(source.textureHash(), source.slimModel(), Map.of(region, url), bake.cacheEpoch)) {
                        if (!isCurrent(bake.generation)) return;
                        throw new IOException("Could not save baked skin texture");
                    }
                    ready = this.cache.load(source.textureHash(), source.slimModel(), bake.cacheEpoch);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException | ExecutionException | RuntimeException exception) {
            fail(key, bake, exception);
        }
    }

    private CompletableFuture<String> texture(byte[] png, boolean slimModel, long expectedGeneration, long cacheEpoch) {
        String contentKey = SkinCache.createContentKey(png, slimModel);
        synchronized (this) {
            if (!isCurrent(expectedGeneration)) return CompletableFuture.failedFuture(new CancellationException());
            CompletableFuture<String> existing = this.uploads.get(contentKey);
            if (existing != null) return existing;
            CompletableFuture<String> result = new CompletableFuture<>();
            this.uploads.put(contentKey, result);
            ensureExecutor();
            this.executor.execute(() -> upload(contentKey, png, slimModel, expectedGeneration, cacheEpoch, result));
            return result;
        }
    }

    private void upload(
        String contentKey,
        byte[] png,
        boolean slimModel,
        long expectedGeneration,
        long cacheEpoch,
        CompletableFuture<String> result
    ) {
        try {
            String url = this.cache.loadContent(contentKey, cacheEpoch);
            if (!isCurrent(expectedGeneration)) { result.cancel(false); return; }
            if (url == null) {
                url = uploadWithSelectedProvider(contentKey, png, slimModel);
                if (!this.cache.saveContent(contentKey, url, cacheEpoch)) {
                    if (!isCurrent(expectedGeneration)) { result.cancel(false); return; }
                    throw new IOException("Could not save uploaded skin texture");
                }
            }
            boolean current;
            synchronized (this) {
                current = isCurrent(expectedGeneration);
                this.uploads.remove(contentKey, result);
            }
            if (current) result.complete(url);
            else result.cancel(false);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            completeUploadExceptionally(contentKey, expectedGeneration, result, exception);
        } catch (IOException | ExecutionException | RuntimeException exception) {
            completeUploadExceptionally(contentKey, expectedGeneration, result, exception);
        }
    }

    private String uploadWithSelectedProvider(String contentKey, byte[] png, boolean slimModel)
        throws IOException, InterruptedException, ExecutionException {
        if (this.accounts.storageError() != null) {
            throw new IOException(this.accounts.storageError());
        }
        if (hasUsableAccount()) {
            try {
                return this.accountUploads.submit(png, slimModel).get();
            } catch (ExecutionException exception) {
                if (!this.accounts.hasAccounts() && this.mineSkin.available()) {
                    return this.mineSkin.upload(contentKey, png, slimModel);
                }
                throw exception;
            }
        }
        if (this.accounts.hasAccounts()) {
            throw new IOException("No usable bake account; run /emote account login");
        }
        if (this.mineSkin.available()) {
            return this.mineSkin.upload(contentKey, png, slimModel);
        }
        throw new IOException("No skin upload provider is available");
    }

    private void completeUploadExceptionally(
        String contentKey,
        long expectedGeneration,
        CompletableFuture<String> result,
        Throwable exception
    ) {
        boolean current;
        synchronized (this) {
            current = isCurrent(expectedGeneration);
            this.uploads.remove(contentKey, result);
        }
        if (current) result.completeExceptionally(exception);
        else result.cancel(false);
    }

    private void fail(PlayerSkinSource.Key key, Bake bake, Throwable exception) {
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
        if (!isCurrent(bake.generation)) return;
        subscribers.forEach(uuid -> currentListener.onFailed(uuid, bake.generation));
        EmoteMod.LOGGER.warn("Skin bake failed for {}; retry later or check /emote account", bake.source.playerUuid(), exception);
    }

    private void cleanupCache(Config config, long expectedGeneration) {
        long cacheEpoch;
        synchronized (this) {
            if (!isCurrent(expectedGeneration)) return;
            cacheEpoch = this.cache.epoch();
        }
        try {
            SkinCache.CleanupResult result = this.cache.cleanup(
                TimeUnit.DAYS.toMillis(config.mineSkinCacheRetentionDays()),
                config.mineSkinCacheMaxMiB() * MEBIBYTE_BYTES,
                System.currentTimeMillis(),
                cacheEpoch
            );
            if (result.totalFilesDeleted() > 0) {
                EmoteMod.LOGGER.info(
                    "Cleaned skin cache by deleting {} expired, {} over-capacity, and {} transient files; retained {} bytes",
                    result.expiredFilesDeleted(),
                    result.capacityFilesDeleted(),
                    result.transientFilesDeleted(),
                    result.retainedBytes()
                );
            }
        } catch (RuntimeException exception) {
            EmoteMod.LOGGER.warn("Failed to clean skin cache", exception);
        }
        synchronized (this) {
            if (isCurrent(expectedGeneration) && this.scheduler != null) {
                this.cacheCleanup = this.scheduler.schedule(
                    () -> cleanupCache(config, expectedGeneration),
                    CACHE_CLEANUP_INTERVAL_MILLIS,
                    TimeUnit.MILLISECONDS
                );
            }
        }
    }

    private boolean isCurrent(PlayerSkinSource.Key key, Bake bake) {
        return isCurrent(bake.generation) && this.bakes.get(key) == bake;
    }

    private boolean hasUploadProvider() {
        return this.accounts.storageError() == null
            && (hasUsableAccount() || (!this.accounts.hasAccounts() && this.mineSkin.available()));
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


    private static final class Bake {
        private final PlayerSkinSource source;
        private final long generation;
        private final long cacheEpoch;
        private final Set<PlayerSkinRegion> requiredRegions = new LinkedHashSet<>();
        private final Set<UUID> subscribers = new LinkedHashSet<>();
        private boolean running;

        private Bake(PlayerSkinSource source, long generation, long cacheEpoch) {
            this.source = source;
            this.generation = generation;
            this.cacheEpoch = cacheEpoch;
        }
    }

    public interface Listener {
        default void onReady(UUID playerUuid, long generation) {}
        default void onUnavailable(UUID playerUuid, long generation) {}
        default void onFailed(UUID playerUuid, long generation) {}
    }

}
