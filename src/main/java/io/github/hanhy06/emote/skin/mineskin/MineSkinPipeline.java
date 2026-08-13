package io.github.hanhy06.emote.skin.mineskin;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.skin.PlayerSkinManager;
import io.github.hanhy06.emote.skin.model.PlayerSkinRegion;
import io.github.hanhy06.emote.skin.model.PreparedPlayerSkin;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class MineSkinPipeline {
    private static final long FAILED_JOB_RETRY_DELAY_MILLIS = 5L * 60L * 1000L;
    private static final long RATE_LIMIT_RETRY_DELAY_MILLIS = 2L * 60L * 1000L;
    private static final long CACHE_CLEANUP_INTERVAL_MILLIS = TimeUnit.DAYS.toMillis(1);
    private static final long MEBIBYTE_BYTES = 1_024L * 1_024L;
    private static final int RATE_LIMIT_RETRY_LIMIT = 3;
    private static final String CACHE_CLEANUP_KEY = "cache-cleanup";

    private final PlayerSkinBaker playerSkinBaker;
    private final MineSkinCache cache;
    private final MineSkinClient client;
    private final MineSkinTaskQueue generationQueue;
    private final Consumer<UUID> readyNotifier;
    private final Consumer<UUID> failureNotifier;
    private final Map<String, MineSkinBakeTask> bakeTasks = new HashMap<>();

    private volatile String apiKey = "";
    private volatile int cacheRetentionDays;
    private volatile int cacheMaxMiB;

    public MineSkinPipeline(
        PlayerSkinBaker playerSkinBaker,
        MineSkinCache cache,
        MineSkinClient client,
        MineSkinTaskQueue generationQueue,
        Consumer<UUID> readyNotifier,
        Consumer<UUID> failureNotifier
    ) {
        this.playerSkinBaker = playerSkinBaker;
        this.cache = cache;
        this.client = client;
        this.generationQueue = generationQueue;
        this.readyNotifier = readyNotifier;
        this.failureNotifier = failureNotifier;
    }

    public void configure(
        String apiKey,
        int pollIntervalSeconds,
        int cacheRetentionDays,
        int cacheMaxMiB
    ) {
        this.apiKey = apiKey;
        this.client.setJobPollIntervalSeconds(pollIntervalSeconds);
        this.cacheRetentionDays = cacheRetentionDays;
        this.cacheMaxMiB = cacheMaxMiB;
        long queueGeneration = this.generationQueue.currentGeneration();
        this.generationQueue.submit(CACHE_CLEANUP_KEY, () -> cleanupCache(queueGeneration));
    }

    public PlayerSkinManager.SkinPreparation prepare(
        PlayerSkinManager.PlayerSkinSource source,
        Set<PlayerSkinRegion> requiredTextureKeys
    ) {
        Map<PlayerSkinRegion, String> savedTextureUrls = loadTextureSet(source, requiredTextureKeys);
        if (savedTextureUrls.size() == requiredTextureKeys.size()) {
            return new PlayerSkinManager.SkinPreparation(
                new PreparedPlayerSkin(savedTextureUrls),
                PlayerSkinManager.SkinPreparationState.READY,
                100
            );
        }
        if (!MineSkinClient.hasApiKey(this.apiKey)) {
            return new PlayerSkinManager.SkinPreparation(
                savedTextureUrls.isEmpty() ? null : new PreparedPlayerSkin(savedTextureUrls),
                PlayerSkinManager.SkinPreparationState.UNAVAILABLE,
                progressPercent(savedTextureUrls.size(), requiredTextureKeys.size())
            );
        }

        MineSkinBakeTask.Stage stage = scheduleBake(source, requiredTextureKeys);
        PlayerSkinManager.SkinPreparationState preparationState = stage == MineSkinBakeTask.Stage.FAILED
            ? PlayerSkinManager.SkinPreparationState.FAILED
            : PlayerSkinManager.SkinPreparationState.PREPARING;
        return new PlayerSkinManager.SkinPreparation(
            savedTextureUrls.isEmpty() ? null : new PreparedPlayerSkin(savedTextureUrls),
            preparationState,
            progressPercent(savedTextureUrls.size(), requiredTextureKeys.size())
        );
    }

    private static int progressPercent(int completedParts, int totalParts) {
        return Math.min(100, completedParts * 100 / totalParts);
    }

    private void notifyCompleted(MineSkinBakeTask bakeTask) {
        for (UUID playerUuid : bakeTask.subscribers()) {
            this.readyNotifier.accept(playerUuid);
        }
    }

    private void notifyFailed(MineSkinBakeTask bakeTask) {
        for (UUID playerUuid : bakeTask.subscribers()) {
            this.failureNotifier.accept(playerUuid);
        }
    }

    private void fail(MineSkinBakeTask bakeTask) {
        if (bakeTask.fail()) {
            notifyFailed(bakeTask);
            this.generationQueue.schedule(
                cleanupKey(bakeTask),
                () -> evictFailedTask(bakeTask),
                FAILED_JOB_RETRY_DELAY_MILLIS,
                bakeTask.queueGeneration()
            );
        }
    }

    private void complete(MineSkinBakeTask bakeTask) {
        if (!bakeTask.complete()) {
            return;
        }
        this.generationQueue.cancelScheduled(cleanupKey(bakeTask));
        notifyCompleted(bakeTask);
        synchronized (this.bakeTasks) {
            if (this.bakeTasks.get(bakeTask.key()) == bakeTask && bakeTask.stage() == MineSkinBakeTask.Stage.COMPLETE) {
                this.bakeTasks.remove(bakeTask.key());
            }
        }
    }

    private void evictFailedTask(MineSkinBakeTask bakeTask) {
        long remainingMillis;
        synchronized (this.bakeTasks) {
            if (this.bakeTasks.get(bakeTask.key()) != bakeTask || bakeTask.stage() != MineSkinBakeTask.Stage.FAILED) {
                return;
            }
            remainingMillis = bakeTask.failedAtEpochMillis()
                + FAILED_JOB_RETRY_DELAY_MILLIS
                - System.currentTimeMillis();
            if (remainingMillis <= 0L) {
                this.bakeTasks.remove(bakeTask.key());
            }
        }
        if (remainingMillis > 0L) {
            this.generationQueue.schedule(
                cleanupKey(bakeTask),
                () -> evictFailedTask(bakeTask),
                remainingMillis,
                bakeTask.queueGeneration()
            );
        }
    }

    private static String cleanupKey(MineSkinBakeTask bakeTask) {
        return "cleanup:" + bakeTask.key();
    }

    int trackedBakeTaskCount() {
        synchronized (this.bakeTasks) {
            return this.bakeTasks.size();
        }
    }

    private void scheduleIfNeeded(
        String pendingKey,
        MineSkinBakeTask bakeTask,
        boolean addedKeys
    ) {
        if (addedKeys || bakeTask.canRestart(System.currentTimeMillis(), FAILED_JOB_RETRY_DELAY_MILLIS)) {
            bakeTask.queue();
            this.generationQueue.submit(pendingKey, () -> bakeAndSave(bakeTask));
        }
    }

    public void cancelPendingBakes() {
        synchronized (this.bakeTasks) {
            for (MineSkinBakeTask bakeTask : this.bakeTasks.values()) {
                bakeTask.cancel();
            }
            this.bakeTasks.clear();
        }
        this.generationQueue.cancelAll();
        this.cache.clearMemory();
    }

    private void cleanupCache(long queueGeneration) {
        try {
            MineSkinCache.CleanupResult result = this.cache.cleanup(
                TimeUnit.DAYS.toMillis(this.cacheRetentionDays),
                this.cacheMaxMiB * MEBIBYTE_BYTES,
                System.currentTimeMillis()
            );
            if (result.totalFilesDeleted() > 0) {
                Emote.LOGGER.info(
                    "Cleaned MineSkin cache: expired={}, capacity={}, transient={}, retained={} bytes",
                    result.expiredFilesDeleted(),
                    result.capacityFilesDeleted(),
                    result.transientFilesDeleted(),
                    result.retainedBytes()
                );
            }
        } catch (RuntimeException exception) {
            Emote.LOGGER.warn("Failed to clean MineSkin cache", exception);
        } finally {
            this.generationQueue.schedule(
                CACHE_CLEANUP_KEY,
                () -> cleanupCache(queueGeneration),
                CACHE_CLEANUP_INTERVAL_MILLIS,
                queueGeneration
            );
        }
    }

    private Map<PlayerSkinRegion, String> loadTextureSet(
        PlayerSkinManager.PlayerSkinSource source,
        Set<PlayerSkinRegion> requiredTextureKeys
    ) {
        Map<PlayerSkinRegion, String> stored = this.cache.load(source.textureHash(), source.slimModel());
        Map<PlayerSkinRegion, String> result = new HashMap<>();
        for (PlayerSkinRegion textureKey : requiredTextureKeys) {
            String textureUrl = stored.get(textureKey);
            if (textureUrl != null) {
                result.put(textureKey, textureUrl);
            }
        }
        return Map.copyOf(result);
    }

    private MineSkinBakeTask.Stage scheduleBake(
        PlayerSkinManager.PlayerSkinSource source,
        Set<PlayerSkinRegion> requiredKeys
    ) {
        String pendingKey = source.textureHash() + ":" + (source.slimModel() ? "slim" : "classic");
        MineSkinBakeTask bakeTask;
        boolean addedKeys;
        synchronized (this.bakeTasks) {
            bakeTask = this.bakeTasks.computeIfAbsent(
                pendingKey,
                ignored -> new MineSkinBakeTask(source, this.generationQueue.currentGeneration())
            );
            bakeTask.addSubscriber(source.playerUuid());
            addedKeys = bakeTask.addRequiredRegions(requiredKeys);
        }
        scheduleIfNeeded(pendingKey, bakeTask, addedKeys);
        return bakeTask.stage();
    }

    private void bakeAndSave(MineSkinBakeTask bakeTask) {
        PlayerSkinManager.PlayerSkinSource source = bakeTask.source();
        try {
            Map<PlayerSkinRegion, String> stored = this.cache.load(source.textureHash(), source.slimModel());
            Set<PlayerSkinRegion> missingKeys = new LinkedHashSet<>(bakeTask.requiredRegions());
            missingKeys.removeAll(stored.keySet());
            if (missingKeys.isEmpty()) {
                complete(bakeTask);
                return;
            }

            bakeTask.updateStage(MineSkinBakeTask.Stage.DOWNLOADING_SKIN);
            BufferedImage sourceImage = this.client.downloadSkinImage(source.textureUrl());
            PlayerSkinBaker.PreparedSkin preparedSkin = this.playerSkinBaker.prepare(
                sourceImage,
                source.slimModel()
            );
            Map<PlayerSkinRegion, String> saved = new HashMap<>(stored);
            for (PlayerSkinRegion textureKey : missingKeys) {
                bakeTask.updateStage(MineSkinBakeTask.Stage.BAKING_PART);
                byte[] bakedImage = this.playerSkinBaker.bake(
                    preparedSkin,
                    textureKey.skinPart(),
                    textureKey.skinSegment()
                );
                String contentHash = MineSkinCache.createContentKey(bakedImage, source.slimModel());
                bakeTask.updateStage(MineSkinBakeTask.Stage.WAITING_FOR_MINESKIN);
                TextureResolution resolution = resolveTextureUrl(this.apiKey, contentHash, bakedImage, source.slimModel());
                if (resolution.retryAtEpochMillis() > 0L) {
                    int retryAttempt = bakeTask.recordRetry(textureKey, resolution.retryAtEpochMillis());
                    if (retryAttempt > RATE_LIMIT_RETRY_LIMIT) {
                        fail(bakeTask);
                        Emote.LOGGER.warn(
                            "MineSkin bake exhausted retries for {} part {}: {}",
                            source.playerName(),
                            textureKey,
                            resolution.errorMessage()
                        );
                        return;
                    }
                    bakeTask.waitForRetry();
                    long retryDelayMillis = Math.max(1L, resolution.retryAtEpochMillis() - System.currentTimeMillis());
                    this.generationQueue.schedule(
                        bakeTask.key(),
                        () -> bakeAndSave(bakeTask),
                        retryDelayMillis,
                        bakeTask.queueGeneration()
                    );
                    Emote.LOGGER.warn(
                        "MineSkin bake rate limited for {} part {}. Retrying in {} ms ({}/{})",
                        source.playerName(),
                        textureKey,
                        retryDelayMillis,
                        retryAttempt,
                        RATE_LIMIT_RETRY_LIMIT
                    );
                    return;
                }
                if (resolution.textureUrl() == null) {
                    fail(bakeTask);
                    return;
                }
                saved.put(textureKey, resolution.textureUrl());
                this.cache.save(source.textureHash(), source.slimModel(), saved);
                bakeTask.markCompleted(textureKey);
            }
            Emote.LOGGER.info("Saved MineSkin bake for {} ({})", source.playerName(), source.textureHash());
            if (bakeTask.isSatisfiedBy(saved.keySet())) {
                complete(bakeTask);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            bakeTask.cancel();
            Emote.LOGGER.warn("MineSkin bake interrupted for {}", source.playerName(), exception);
        } catch (IOException | RuntimeException exception) {
            fail(bakeTask);
            Emote.LOGGER.warn("MineSkin bake failed for {}", source.playerName(), exception);
        }
    }

    private TextureResolution resolveTextureUrl(
        String currentApiKey,
        String contentHash,
        byte[] bakedImage,
        boolean slimModel
    ) throws IOException, InterruptedException {
        String cachedTextureUrl = this.cache.loadContent(contentHash);
        if (cachedTextureUrl != null) {
            return TextureResolution.ready(cachedTextureUrl);
        }

        long now = System.currentTimeMillis();
        MineSkinCache.MineSkinFailure failure = this.cache.loadFailure(contentHash, now);
        if (failure != null) {
            return TextureResolution.retry(failure.retryAfterEpochMillis(), failure.errorMessage());
        }
        MineSkinCache.MineSkinPendingJob pendingJob = this.cache.loadPendingJob(contentHash);
        if (pendingJob != null
            && now - pendingJob.submittedAtEpochMillis() > MineSkinCache.PENDING_JOB_MAX_AGE_MILLIS) {
            this.cache.clearPendingJob(contentHash);
            pendingJob = null;
        }

        String textureUrl;
        try {
            if (pendingJob != null) {
                textureUrl = this.client.waitForSkinUrl(currentApiKey, pendingJob.jobId());
            } else {
                textureUrl = this.client.generateSkinUrl(
                    currentApiKey,
                    bakedImage,
                    slimModel,
                    jobId -> this.cache.savePendingJob(contentHash, jobId)
                );
            }
        } catch (MineSkinClient.RateLimitException exception) {
            long retryAt = now + positiveOrRateLimitFallback(exception.retryDelayMillis());
            this.cache.saveFailure(contentHash, exception.getMessage(), retryAt);
            return TextureResolution.retry(retryAt, exception.getMessage());
        } catch (MineSkinClient.JobFailedException exception) {
            this.cache.clearPendingJob(contentHash);
            if (exception.isRateLimited()) {
                long retryAt = now + positiveOrRateLimitFallback(exception.retryDelayMillis());
                this.cache.saveFailure(contentHash, exception.getMessage(), retryAt);
                return TextureResolution.retry(retryAt, exception.getMessage());
            } else {
                this.cache.saveFailure(contentHash, exception.getMessage(), now + FAILED_JOB_RETRY_DELAY_MILLIS);
                Emote.LOGGER.warn("MineSkin rejected baked texture {}: {}", contentHash, exception.getMessage());
                return TextureResolution.failed(exception.getMessage());
            }
        }

        this.cache.saveContent(contentHash, textureUrl);
        this.cache.clearPendingJob(contentHash);
        this.cache.clearFailure(contentHash);
        return TextureResolution.ready(textureUrl);
    }

    private static long positiveOrRateLimitFallback(long value) {
        return value > 0L ? value : RATE_LIMIT_RETRY_DELAY_MILLIS;
    }

    private record TextureResolution(String textureUrl, long retryAtEpochMillis, String errorMessage) {
        private static TextureResolution ready(String textureUrl) {
            return new TextureResolution(Objects.requireNonNull(textureUrl, "textureUrl"), 0L, null);
        }

        private static TextureResolution retry(long retryAtEpochMillis, String errorMessage) {
            return new TextureResolution(null, retryAtEpochMillis, Objects.requireNonNull(errorMessage, "errorMessage"));
        }

        private static TextureResolution failed(String errorMessage) {
            return new TextureResolution(null, 0L, Objects.requireNonNull(errorMessage, "errorMessage"));
        }
    }

}
