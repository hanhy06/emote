package io.github.hanhy06.emote.skin;

import io.github.hanhy06.emote.Emote;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

final class MineSkinManager {
    private static final long PENDING_JOB_MAX_AGE_MILLIS = 35L * 60L * 1000L;
    private static final long FAILED_JOB_RETRY_DELAY_MILLIS = 5L * 60L * 1000L;
    private static final long RATE_LIMIT_RETRY_DELAY_MILLIS = 2L * 60L * 1000L;
    private static final int RATE_LIMIT_RETRY_LIMIT = 3;

    private final PlayerSkinBaker playerSkinBaker;
    private final MineSkinCache cache;
    private final MineSkinClient client;
    private final MineSkinGenerationQueue generationQueue;
    private final Consumer<UUID> readyNotifier;
    private final Consumer<UUID> failureNotifier;
    private final Map<String, BakeTask> bakeTasks = new HashMap<>();

    private volatile String apiKey = "";

    MineSkinManager(
        PlayerSkinBaker playerSkinBaker,
        MineSkinCache cache,
        MineSkinClient client,
        MineSkinGenerationQueue generationQueue,
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

    void configure(String apiKey, int pollIntervalSeconds) {
        this.apiKey = apiKey;
        this.client.setJobPollIntervalSeconds(pollIntervalSeconds);
    }

    Preparation prepare(
        PlayerSkinManager.PlayerSkinSource source,
        Set<PlayerSkinTextureKey> requiredTextureKeys
    ) {
        Map<PlayerSkinTextureKey, String> savedTextureUrls = loadTextureSet(source, requiredTextureKeys);
        if (savedTextureUrls.size() == requiredTextureKeys.size()) {
            return new Preparation(
                new PreparedPlayerSkin(savedTextureUrls),
                PreparationState.READY,
                100
            );
        }
        if (!MineSkinClient.hasApiKey(this.apiKey)) {
            return new Preparation(
                savedTextureUrls.isEmpty() ? null : new PreparedPlayerSkin(savedTextureUrls),
                PreparationState.UNAVAILABLE,
                progressPercent(savedTextureUrls.size(), requiredTextureKeys.size())
            );
        }

        scheduleBake(source, requiredTextureKeys);
        BakeStage stage = findBakeStage(source);
        PreparationState preparationState = stage == BakeStage.FAILED
            ? PreparationState.FAILED
            : PreparationState.PREPARING;
        return new Preparation(
            savedTextureUrls.isEmpty() ? null : new PreparedPlayerSkin(savedTextureUrls),
            preparationState,
            progressPercent(savedTextureUrls.size(), requiredTextureKeys.size())
        );
    }

    private static int progressPercent(int completedParts, int totalParts) {
        if (totalParts <= 0) {
            return 100;
        }
        return Math.min(100, completedParts * 100 / totalParts);
    }

    private BakeStage findBakeStage(PlayerSkinManager.PlayerSkinSource source) {
        String pendingKey = source.textureHash() + ":" + (source.slimModel() ? "slim" : "classic");
        synchronized (this.bakeTasks) {
            BakeTask bakeTask = this.bakeTasks.get(pendingKey);
            return bakeTask == null ? BakeStage.QUEUED : bakeTask.stage();
        }
    }

    private void notifyCompleted(BakeTask bakeTask) {
        for (UUID playerUuid : bakeTask.subscribers()) {
            this.readyNotifier.accept(playerUuid);
        }
    }

    private void notifyFailed(BakeTask bakeTask) {
        for (UUID playerUuid : bakeTask.subscribers()) {
            this.failureNotifier.accept(playerUuid);
        }
    }

    private void fail(BakeTask bakeTask) {
        if (bakeTask.fail()) {
            notifyFailed(bakeTask);
            this.generationQueue.schedule(
                cleanupKey(bakeTask),
                () -> evictFailedTask(bakeTask),
                FAILED_JOB_RETRY_DELAY_MILLIS
            );
        }
    }

    private void complete(BakeTask bakeTask) {
        if (!bakeTask.complete()) {
            return;
        }
        this.generationQueue.cancelScheduled(cleanupKey(bakeTask));
        notifyCompleted(bakeTask);
        synchronized (this.bakeTasks) {
            if (this.bakeTasks.get(bakeTask.key()) == bakeTask && bakeTask.stage() == BakeStage.COMPLETE) {
                this.bakeTasks.remove(bakeTask.key());
            }
        }
    }

    private void evictFailedTask(BakeTask bakeTask) {
        long remainingMillis;
        synchronized (this.bakeTasks) {
            if (this.bakeTasks.get(bakeTask.key()) != bakeTask || bakeTask.stage() != BakeStage.FAILED) {
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
                remainingMillis
            );
        }
    }

    private static String cleanupKey(BakeTask bakeTask) {
        return "cleanup:" + bakeTask.key();
    }

    int trackedBakeTaskCount() {
        synchronized (this.bakeTasks) {
            return this.bakeTasks.size();
        }
    }

    private void scheduleIfNeeded(
        String pendingKey,
        BakeTask bakeTask,
        boolean addedKeys
    ) {
        if (addedKeys || bakeTask.canRestart(System.currentTimeMillis())) {
            bakeTask.queue();
            this.generationQueue.submit(pendingKey, () -> bakeAndSave(bakeTask));
        }
    }

    void cancelPendingBakes() {
        this.generationQueue.cancelAll();
        synchronized (this.bakeTasks) {
            for (BakeTask bakeTask : this.bakeTasks.values()) {
                bakeTask.cancel();
            }
            this.bakeTasks.clear();
        }
        this.cache.clearMemory();
    }

    private Map<PlayerSkinTextureKey, String> loadTextureSet(
        PlayerSkinManager.PlayerSkinSource source,
        Set<PlayerSkinTextureKey> requiredTextureKeys
    ) {
        Map<PlayerSkinTextureKey, String> stored = this.cache.load(source.textureHash(), source.slimModel());
        Map<PlayerSkinTextureKey, String> result = new HashMap<>();
        for (PlayerSkinTextureKey textureKey : requiredTextureKeys) {
            String textureUrl = stored.get(textureKey);
            if (textureUrl != null) {
                result.put(textureKey, textureUrl);
            }
        }
        return Map.copyOf(result);
    }

    private void scheduleBake(PlayerSkinManager.PlayerSkinSource source, Set<PlayerSkinTextureKey> requiredKeys) {
        if (!MineSkinClient.hasApiKey(this.apiKey)) {
            return;
        }
        String pendingKey = source.textureHash() + ":" + (source.slimModel() ? "slim" : "classic");
        BakeTask bakeTask;
        boolean addedKeys;
        synchronized (this.bakeTasks) {
            bakeTask = this.bakeTasks.computeIfAbsent(pendingKey, ignored -> new BakeTask(source));
            bakeTask.addSubscriber(source.playerUuid());
            addedKeys = bakeTask.addRequiredKeys(requiredKeys);
        }
        scheduleIfNeeded(pendingKey, bakeTask, addedKeys);
    }

    private void bakeAndSave(BakeTask bakeTask) {
        PlayerSkinManager.PlayerSkinSource source = bakeTask.source();
        try {
            Map<PlayerSkinTextureKey, String> stored = this.cache.load(source.textureHash(), source.slimModel());
            Set<PlayerSkinTextureKey> missingKeys = new LinkedHashSet<>(bakeTask.requiredKeys());
            missingKeys.removeAll(stored.keySet());
            if (missingKeys.isEmpty()) {
                complete(bakeTask);
                return;
            }

            bakeTask.updateStage(BakeStage.DOWNLOADING_SKIN);
            BufferedImage sourceImage = this.client.downloadSkinImage(source.textureUrl());
            Map<PlayerSkinTextureKey, String> saved = new HashMap<>(stored);
            for (PlayerSkinTextureKey textureKey : missingKeys) {
                bakeTask.updateStage(BakeStage.BAKING_PART);
                byte[] bakedImage = this.playerSkinBaker.bake(
                    sourceImage,
                    textureKey.skinPart(),
                    textureKey.skinSegment(),
                    source.slimModel()
                );
                String contentHash = MineSkinCache.createContentKey(bakedImage, source.slimModel());
                bakeTask.updateStage(BakeStage.WAITING_FOR_MINESKIN);
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
                        retryDelayMillis
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
        } catch (IOException | IllegalArgumentException exception) {
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
        if (pendingJob != null && now - pendingJob.submittedAtEpochMillis() > PENDING_JOB_MAX_AGE_MILLIS) {
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

    enum BakeStage {
        QUEUED,
        DOWNLOADING_SKIN,
        BAKING_PART,
        WAITING_FOR_MINESKIN,
        RETRY_WAIT,
        COMPLETE,
        FAILED,
        CANCELLED
    }

    enum PreparationState {
        READY,
        PREPARING,
        FAILED,
        UNAVAILABLE
    }

    record Preparation(PreparedPlayerSkin preparedSkin, PreparationState state, int progressPercent) {
        Preparation {
            Objects.requireNonNull(state, "state");
            if (progressPercent < 0 || progressPercent > 100) {
                throw new IllegalArgumentException("progressPercent must be between 0 and 100");
            }
        }
    }

    private static final class BakeTask {
        private final String key;
        private final PlayerSkinManager.PlayerSkinSource source;
        private final Set<UUID> subscribers = new LinkedHashSet<>();
        private final Set<PlayerSkinTextureKey> requiredKeys = new LinkedHashSet<>();
        private final Map<PlayerSkinTextureKey, Integer> retryAttempts = new HashMap<>();
        private final Map<PlayerSkinTextureKey, Long> retryTimes = new HashMap<>();
        private BakeStage stage = BakeStage.QUEUED;
        private long failedAtEpochMillis;

        private BakeTask(PlayerSkinManager.PlayerSkinSource source) {
            this.source = source;
            this.key = source.textureHash() + ":" + (source.slimModel() ? "slim" : "classic");
        }

        private String key() {
            return this.key;
        }

        private PlayerSkinManager.PlayerSkinSource source() {
            return this.source;
        }

        private synchronized void addSubscriber(UUID playerUuid) {
            this.subscribers.add(playerUuid);
        }

        private synchronized boolean addRequiredKeys(Set<PlayerSkinTextureKey> keys) {
            boolean changed = this.requiredKeys.addAll(keys);
            if (changed && (this.stage == BakeStage.COMPLETE || this.stage == BakeStage.FAILED)) {
                this.stage = BakeStage.QUEUED;
            }
            return changed;
        }

        private synchronized Set<PlayerSkinTextureKey> requiredKeys() {
            return Set.copyOf(this.requiredKeys);
        }

        private synchronized Set<UUID> subscribers() {
            return Set.copyOf(this.subscribers);
        }

        private synchronized BakeStage stage() {
            return this.stage;
        }

        private synchronized void queue() {
            this.stage = BakeStage.QUEUED;
        }

        private synchronized boolean canRestart(long nowEpochMillis) {
            return this.stage == BakeStage.FAILED
                && nowEpochMillis - this.failedAtEpochMillis >= FAILED_JOB_RETRY_DELAY_MILLIS;
        }

        private synchronized long failedAtEpochMillis() {
            return this.failedAtEpochMillis;
        }

        private synchronized void updateStage(BakeStage stage) {
            this.stage = stage;
        }

        private synchronized void markCompleted(PlayerSkinTextureKey textureKey) {
            this.retryAttempts.remove(textureKey);
            this.retryTimes.remove(textureKey);
        }

        private synchronized int recordRetry(PlayerSkinTextureKey textureKey, long retryAtEpochMillis) {
            Long previousRetryAt = this.retryTimes.put(textureKey, retryAtEpochMillis);
            if (previousRetryAt != null && previousRetryAt == retryAtEpochMillis) {
                return this.retryAttempts.getOrDefault(textureKey, 1);
            }
            return this.retryAttempts.merge(textureKey, 1, Integer::sum);
        }

        private synchronized void waitForRetry() {
            this.stage = BakeStage.RETRY_WAIT;
        }

        private synchronized boolean isSatisfiedBy(Set<PlayerSkinTextureKey> availableKeys) {
            return availableKeys.containsAll(this.requiredKeys);
        }

        private synchronized boolean complete() {
            boolean changed = this.stage != BakeStage.COMPLETE;
            this.stage = BakeStage.COMPLETE;
            return changed;
        }

        private synchronized boolean fail() {
            boolean changed = this.stage != BakeStage.FAILED;
            this.stage = BakeStage.FAILED;
            this.failedAtEpochMillis = System.currentTimeMillis();
            return changed;
        }

        private synchronized void cancel() {
            this.stage = BakeStage.CANCELLED;
        }
    }
}
