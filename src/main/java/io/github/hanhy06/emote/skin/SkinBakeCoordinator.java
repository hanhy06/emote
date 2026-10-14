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
import java.util.*;
import java.util.concurrent.*;

public final class SkinBakeCoordinator implements ConfigListener {
    private static final long FAILED_BAKE_RETRY_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private static final long CACHE_CLEANUP_INTERVAL_MILLIS = TimeUnit.DAYS.toMillis(1);
    private static final long MEBIBYTE_BYTES = 1_024L * 1_024L;
    private static final UUID DEFAULT_SUBSCRIBER = new UUID(0L, 0L);

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
    private final Object defaultIoLock = new Object();
    private long defaultRevision;
    private SkinCache.DefaultSkin savedDefault;
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
        MineSkinProvider mineSkin
    ) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.baker = Objects.requireNonNull(baker, "baker");
        this.skinClient = Objects.requireNonNull(skinClient, "skinClient");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.accountUploads = Objects.requireNonNull(accountUploads, "accountUploads");
        this.mineSkin = Objects.requireNonNull(mineSkin, "mineSkin");
    }

    public synchronized Map<PlayerSkinRegion, String> defaultSkin() { return this.defaultSkin; }

    public synchronized void setDefaultRegions(Set<PlayerSkinRegion> regions) {
        this.defaultRegions = Set.copyOf(regions);
        prepareDefault();
    }

    public synchronized void setDefaultSource(PlayerSkinSource source) {
        if (this.defaultName.isEmpty()) return;
        this.defaultSource = new PlayerSkinSource(DEFAULT_SUBSCRIBER, this.defaultName,
            source.textureHash(), source.textureUrl(), source.slimModel());
        this.failures.remove(new PlayerSkinSource.Key(source.textureHash(), source.slimModel()));
        prepareDefault();
    }

    private void prepareDefault() {
        long revision = ++this.defaultRevision;
        if (this.stopping || this.defaultName.isEmpty()) return;
        long expectedGeneration = this.generation;
        long cacheEpoch = this.cache.epoch();
        ensureExecutor();
        this.executor.execute(() -> prepareDefault(expectedGeneration, revision, cacheEpoch));
    }

    private void prepareDefault(long expectedGeneration, long revision, long cacheEpoch) {
        Listener readyListener = null;
        synchronized (this.defaultIoLock) {
            String name;
            synchronized (this) {
                if (!isDefaultCurrent(expectedGeneration, revision)) return;
                name = this.defaultName;
            }
            SkinCache.DefaultSkin stored = this.cache.loadDefault(name, cacheEpoch);
            PlayerSkinSource source;
            Set<PlayerSkinRegion> regions;
            synchronized (this) {
                if (!isDefaultCurrent(expectedGeneration, revision)) return;
                this.savedDefault = stored;
                if (stored != null) {
                    this.defaultSkin = stored.textures();
                    readyListener = this.listener;
                    if (this.defaultSource == null) {
                        this.defaultSource = new PlayerSkinSource(DEFAULT_SUBSCRIBER, name, stored.textureHash(), stored.textureUrl(), stored.slimModel());
                        this.failures.remove(new PlayerSkinSource.Key(stored.textureHash(), stored.slimModel()));
                    }
                }
                source = this.defaultSource;
                regions = this.defaultRegions;
            }
            if (source != null && !regions.isEmpty()) {
                if (stored != null && stored.textureHash().equals(source.textureHash()) && stored.slimModel() == source.slimModel()) {
                    this.cache.save(source.textureHash(), source.slimModel(), stored.textures(), cacheEpoch);
                }
                synchronized (this) {
                    if (!isDefaultCurrent(expectedGeneration, revision)) return;
                    prepare(source, regions);
                }
            }
        }
        if (readyListener != null && isDefaultCurrent(expectedGeneration, revision)) readyListener.onDefaultReady(expectedGeneration, revision);
        publishDefault(expectedGeneration, revision, cacheEpoch);
    }

    private void publishDefault(long expectedGeneration, long revision, long cacheEpoch) {
        Listener readyListener = null;
        synchronized (this.defaultIoLock) {
            PlayerSkinSource source;
            Set<PlayerSkinRegion> regions;
            SkinCache.DefaultSkin previous;
            synchronized (this) {
                if (!isDefaultCurrent(expectedGeneration, revision) || this.defaultSource == null || this.defaultRegions.isEmpty()) return;
                source = this.defaultSource;
                regions = this.defaultRegions;
                previous = this.savedDefault;
            }
            Map<PlayerSkinRegion, String> ready = this.cache.load(source.textureHash(), source.slimModel(), cacheEpoch);
            if (ready.isEmpty()) return;
            boolean changingSource = previous != null && (!previous.textureHash().equals(source.textureHash()) || previous.slimModel() != source.slimModel());
            if (changingSource && !ready.keySet().containsAll(regions)) return;
            synchronized (this) { if (!isDefaultCurrent(expectedGeneration, revision)) return; }
            var saved = new SkinCache.DefaultSkin(source.playerName(), source.textureHash(), source.textureUrl(), source.slimModel(), ready);
            if (!this.cache.saveDefault(saved, cacheEpoch)) return;
            synchronized (this) {
                if (!isDefaultCurrent(expectedGeneration, revision)) return;
                this.savedDefault = saved;
                if (!ready.equals(this.defaultSkin)) {
                    this.defaultSkin = ready;
                    readyListener = this.listener;
                }
            }
        }
        if (readyListener != null && isDefaultCurrent(expectedGeneration, revision)) readyListener.onDefaultReady(expectedGeneration, revision);
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
        this.defaultName = config.defaultSkin();
        this.defaultSource = null;
        this.defaultSkin = null;
        this.savedDefault = null;
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

    public synchronized void setListener(Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public void cancelPendingBakes() {
        List<CompletableFuture<String>> pendingUploads;
        synchronized (this) {
            this.generation++;
            this.stopping = true;
            this.defaultRevision++;
            this.defaultSource = null;
            this.defaultSkin = null;
            this.defaultName = "";
            this.savedDefault = null;
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
    synchronized boolean isDefaultCurrent(long expectedGeneration, long revision) { return isCurrent(expectedGeneration) && revision == this.defaultRevision; }

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
                long defaultRevision = -1;
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
                    if (bake.subscribers.contains(DEFAULT_SUBSCRIBER) && this.defaultSource != null
                        && source.textureHash().equals(this.defaultSource.textureHash()) && source.slimModel() == this.defaultSource.slimModel()) {
                        defaultRevision = this.defaultRevision;
                    }
                }
                if (completedSubscribers != null) {
                    if (defaultRevision >= 0) publishDefault(bake.generation, defaultRevision, bake.cacheEpoch);
                    if (!isCurrent(bake.generation)) return;
                    for (UUID subscriber : completedSubscribers) {
                        if (subscriber.equals(DEFAULT_SUBSCRIBER)) continue;
                        if (unavailable) completionListener.onUnavailable(subscriber, bake.generation);
                        else completionListener.onReady(subscriber, bake.generation);
                    }
                    return;
                }

                if (prepared == null) {
                    BufferedImage sourceImage = this.skinClient.downloadSkin(source.textureUrl());
                    prepared = this.baker.prepare(sourceImage, source.slimModel());
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
                    synchronized (this) {
                        if (!isCurrent(key, bake)) {
                            return;
                        }
                        defaultRevision = -1;
                        if (bake.subscribers.contains(DEFAULT_SUBSCRIBER) && this.defaultSource != null
                            && source.textureHash().equals(this.defaultSource.textureHash())
                            && source.slimModel() == this.defaultSource.slimModel()) {
                            defaultRevision = this.defaultRevision;
                        }
                    }
                    if (defaultRevision >= 0) publishDefault(bake.generation, defaultRevision, bake.cacheEpoch);
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
        subscribers.stream().filter(uuid -> !uuid.equals(DEFAULT_SUBSCRIBER)).forEach(uuid -> currentListener.onFailed(uuid, bake.generation));
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
        default void onDefaultReady(long generation, long revision) {}
        default void onReady(UUID playerUuid, long generation) {}
        default void onUnavailable(UUID playerUuid, long generation) {}
        default void onFailed(UUID playerUuid, long generation) {}
    }

}
