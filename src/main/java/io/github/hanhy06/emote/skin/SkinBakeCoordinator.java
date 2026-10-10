package io.github.hanhy06.emote.skin;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.config.Config;
import io.github.hanhy06.emote.skin.account.AccountBakeQueue;
import io.github.hanhy06.emote.skin.account.MinecraftAccountManager;
import io.github.hanhy06.emote.skin.account.MinecraftSkinClient;
import io.github.hanhy06.emote.skin.model.PlayerSkinPreparation;
import io.github.hanhy06.emote.skin.model.PlayerSkinRegion;
import io.github.hanhy06.emote.skin.model.PlayerSkinSource;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public final class SkinBakeCoordinator implements PlayerSkinProvider {
    private static final long FAILED_BAKE_RETRY_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private static final long CACHE_CLEANUP_INTERVAL_MILLIS = TimeUnit.DAYS.toMillis(1);
    private static final long MEBIBYTE_BYTES = 1_024L * 1_024L;
    private static final UUID DEFAULT_SUBSCRIBER = new UUID(0L, 0L);

    private final MinecraftAccountManager accounts;
    private final PlayerSkinBaker baker;
    private final MinecraftSkinClient skinClient;
    private final SkinCache cache;
    private final AccountBakeQueue accountUploads;
    private final FallbackUploader fallbackUploader;
    private final Map<PlayerSkinSource.Key, Bake> bakes = new HashMap<>();
    private final Map<String, CompletableFuture<String>> uploads = new HashMap<>();
    private final Map<PlayerSkinSource.Key, Long> failures = new LinkedHashMap<>();

    private ExecutorService executor;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> cacheCleanup;
    private Listener listener = new Listener() {};
    private long generation;
    private String defaultName = "";
    private Set<PlayerSkinRegion> defaultRegions = Set.of();
    private PlayerSkinSource defaultSource;
    private Map<PlayerSkinRegion, String> defaultSkin;

    public SkinBakeCoordinator(
        MinecraftAccountManager accounts,
        PlayerSkinBaker baker,
        MinecraftSkinClient skinClient,
        SkinCache cache,
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

    @Override public synchronized Map<PlayerSkinRegion, String> defaultSkin() { return this.defaultSkin; }

    @Override
    public synchronized void setDefaultRegions(Set<PlayerSkinRegion> regions) {
        this.defaultRegions = Set.copyOf(regions);
        prepareDefault();
    }

    @Override
    public synchronized void setDefaultSource(PlayerSkinSource source) {
        if (this.defaultName.isEmpty()) return;
        this.defaultSource = new PlayerSkinSource(DEFAULT_SUBSCRIBER, this.defaultName,
            source.textureHash(), source.textureUrl(), source.slimModel());
        this.failures.remove(new PlayerSkinSource.Key(source.textureHash(), source.slimModel()));
        prepareDefault();
    }

    private synchronized void prepareDefault() {
        if (this.defaultSource == null || this.defaultRegions.isEmpty()) return;
        // Seed the normal bake cache from the pinned result so only new regions need uploading.
        SkinCache.DefaultSkin stored = this.cache.loadDefault(this.defaultName);
        if (stored != null && stored.textureHash().equals(this.defaultSource.textureHash())
            && stored.slimModel() == this.defaultSource.slimModel()) {
            this.cache.save(stored.textureHash(), stored.slimModel(), stored.textures());
        }
        PlayerSkinPreparation preparation = prepare(this.defaultSource, this.defaultRegions);
        if (preparation.state() == PlayerSkinPreparation.State.READY && preparation.textures() != null) {
            publishDefault();
        }
    }

    private synchronized void publishDefault() {
        if (this.defaultSource == null || this.defaultRegions.isEmpty()) return;
        Map<PlayerSkinRegion, String> ready = this.cache.load(this.defaultSource.textureHash(), this.defaultSource.slimModel());
        if (ready.isEmpty()) return;
        SkinCache.DefaultSkin previous = this.cache.loadDefault(this.defaultName);
        boolean changingSource = previous != null && (!previous.textureHash().equals(this.defaultSource.textureHash())
            || previous.slimModel() != this.defaultSource.slimModel());
        // Keep the previous complete skin while a changed source is still being prepared.
        if (changingSource && !ready.keySet().containsAll(this.defaultRegions)) return;
        var saved = new SkinCache.DefaultSkin(this.defaultName, this.defaultSource.textureHash(), this.defaultSource.textureUrl(),
            this.defaultSource.slimModel(), ready);
        if (!this.cache.saveDefault(saved)) return;
        Map<PlayerSkinRegion, String> updated = Map.copyOf(ready);
        if (!updated.equals(this.defaultSkin)) {
            this.defaultSkin = updated;
            this.listener.onDefaultReady();
        }
    }

    @Override
    public synchronized PlayerSkinPreparation prepare(PlayerSkinSource source, Set<PlayerSkinRegion> requiredRegions) {
        Map<PlayerSkinRegion, String> ready = loadReady(source, requiredRegions);
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
        this.defaultName = config.defaultSkin();
        this.defaultSource = null;
        this.defaultSkin = null;
        SkinCache.DefaultSkin stored = this.cache.loadDefault(this.defaultName);
        if (stored != null) {
            this.defaultSkin = Map.copyOf(stored.textures());
            this.defaultSource = new PlayerSkinSource(DEFAULT_SUBSCRIBER, this.defaultName, stored.textureHash(), stored.textureUrl(), stored.slimModel());
        }
        if (this.defaultSource != null) this.failures.remove(new PlayerSkinSource.Key(this.defaultSource.textureHash(), this.defaultSource.slimModel()));
        prepareDefault();
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

    @Override
    public synchronized void setListener(Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public void cancelPendingBakes() {
        List<CompletableFuture<String>> pendingUploads;
        synchronized (this) {
            this.generation++;
            this.defaultSource = null;
            this.defaultSkin = null;
            this.defaultName = "";
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

    private void bake(PlayerSkinSource.Key key, Bake bake) {
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
                Set<UUID> completedSubscribers = null;
                Listener completionListener = null;
                synchronized (this) {
                    if (!isCurrent(key, bake)) {
                        return;
                    }
                    missing = new LinkedHashSet<>(bake.requiredRegions);
                    missing.removeAll(this.cache.load(source.textureHash(), source.slimModel()).keySet());
                    if (missing.isEmpty()) {
                        this.bakes.remove(key, bake);
                        completedSubscribers = Set.copyOf(bake.subscribers);
                        completionListener = this.listener;
                    }
                }
                if (completedSubscribers != null) {
                    Listener listener = completionListener;
                    if (completedSubscribers.contains(DEFAULT_SUBSCRIBER)) publishDefault();
                    completedSubscribers.stream().filter(uuid -> !uuid.equals(DEFAULT_SUBSCRIBER)).forEach(listener::onReady);
                    return;
                }

                for (PlayerSkinRegion region : missing) {
                    byte[] png = this.baker.bake(prepared, region);
                    String url = texture(png, source.slimModel(), bake.generation).get();
                    synchronized (this) {
                        if (!isCurrent(key, bake)) {
                            return;
                        }
                        this.cache.save(source.textureHash(), source.slimModel(), Map.of(region, url));
                        if (!url.equals(this.cache.load(source.textureHash(), source.slimModel()).get(region))) {
                            throw new IOException("Could not save baked skin texture");
                        }
                        if (bake.subscribers.contains(DEFAULT_SUBSCRIBER) && this.defaultSource != null
                            && source.textureHash().equals(this.defaultSource.textureHash())
                            && source.slimModel() == this.defaultSource.slimModel()) {
                            publishDefault();
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
        String contentKey = SkinCache.createContentKey(png, slimModel);
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
            String url = uploadWithSelectedProvider(contentKey, png, slimModel);
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

    private String uploadWithSelectedProvider(String contentKey, byte[] png, boolean slimModel)
        throws IOException, InterruptedException, ExecutionException {
        if (this.accounts.storageError() != null) {
            throw new IOException(this.accounts.storageError());
        }
        if (hasUsableAccount()) {
            try {
                return this.accountUploads.submit(png, slimModel).get();
            } catch (ExecutionException exception) {
                if (!this.accounts.hasAccounts() && this.fallbackUploader.available()) {
                    return this.fallbackUploader.upload(contentKey, png, slimModel);
                }
                throw exception;
            }
        }
        if (this.accounts.hasAccounts()) {
            throw new IOException("No usable bake account; run /emote account login");
        }
        if (this.fallbackUploader.available()) {
            return this.fallbackUploader.upload(contentKey, png, slimModel);
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
        subscribers.stream().filter(uuid -> !uuid.equals(DEFAULT_SUBSCRIBER)).forEach(currentListener::onFailed);
        EmoteMod.LOGGER.warn("Skin bake failed for {}; retry later or check /emote account", bake.source.playerUuid(), exception);
    }

    private void cleanupCache(Config config, long expectedGeneration) {
        synchronized (this) {
            if (expectedGeneration != this.generation) {
                return;
            }
        }
        try {
            SkinCache.CleanupResult result = this.cache.cleanup(
                TimeUnit.DAYS.toMillis(config.mineSkinCacheRetentionDays()),
                config.mineSkinCacheMaxMiB() * MEBIBYTE_BYTES,
                System.currentTimeMillis()
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

    private boolean isCurrent(PlayerSkinSource.Key key, Bake bake) {
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

        String upload(String contentKey, byte[] png, boolean slimModel) throws IOException, InterruptedException;
    }
}
