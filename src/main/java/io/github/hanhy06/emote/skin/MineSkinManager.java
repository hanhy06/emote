package io.github.hanhy06.emote.skin;

import io.github.hanhy06.emote.Emote;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class MineSkinManager {
    private static final long PENDING_JOB_MAX_AGE_MILLIS = 35L * 60L * 1000L;
    private static final long FAILED_JOB_RETRY_DELAY_MILLIS = 5L * 60L * 1000L;
    private static final long RATE_LIMIT_RETRY_DELAY_MILLIS = 2L * 60L * 1000L;

    private final PlayerSkinBaker playerSkinBaker;
    private final MineSkinCache cache;
    private final MineSkinClient client;
    private final GenerationQueue generationQueue;
    private final Consumer<UUID> readyNotifier;
    private final Map<String, BakeTask> bakeTasks = new HashMap<>();

    private volatile String apiKey = "";

    MineSkinManager(
        PlayerSkinBaker playerSkinBaker,
        MineSkinCache cache,
        MineSkinClient client,
        GenerationQueue generationQueue,
        Consumer<UUID> readyNotifier
    ) {
        this.playerSkinBaker = playerSkinBaker;
        this.cache = cache;
        this.client = client;
        this.generationQueue = generationQueue;
        this.readyNotifier = readyNotifier;
    }

    void configure(String apiKey, int pollIntervalSeconds) {
        this.apiKey = apiKey;
        this.client.setJobPollIntervalSeconds(pollIntervalSeconds);
    }

    PreparedPlayerSkin prepare(
        PlayerSkinManager.PlayerSkinSource source,
        Set<PlayerSkinTextureKey> requiredTextureKeys
    ) {
        if (!MineSkinClient.hasApiKey(this.apiKey)) {
            return null;
        }

        Map<PlayerSkinTextureKey, String> savedTextureUrls = loadTextureSet(source, requiredTextureKeys);
        if (savedTextureUrls.size() < requiredTextureKeys.size()) {
            scheduleBake(source, requiredTextureKeys);
        }
        return savedTextureUrls.isEmpty() ? null : new PreparedPlayerSkin(savedTextureUrls);
    }

    void cancelPendingBakes() {
        this.generationQueue.cancelAll();
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
        String currentApiKey = this.apiKey;
        if (!MineSkinClient.hasApiKey(currentApiKey)) {
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
        if (addedKeys) {
            this.generationQueue.submit(pendingKey, () -> bakeAndSave(currentApiKey, bakeTask));
        }
    }

    private void bakeAndSave(
        String currentApiKey,
        BakeTask bakeTask
    ) {
        PlayerSkinManager.PlayerSkinSource source = bakeTask.source();
        try {
            Map<PlayerSkinTextureKey, String> stored = this.cache.load(source.textureHash(), source.slimModel());
            Set<PlayerSkinTextureKey> missingKeys = new LinkedHashSet<>(bakeTask.requiredKeys());
            missingKeys.removeAll(stored.keySet());
            if (missingKeys.isEmpty()) {
                bakeTask.complete();
                return;
            }

            bakeTask.updateStage(BakeStage.DOWNLOADING_SKIN, null);
            BufferedImage sourceImage = this.client.downloadSkinImage(source.textureUrl());
            Map<PlayerSkinTextureKey, String> saved = new HashMap<>(stored);
            boolean savedAny = false;
            for (PlayerSkinTextureKey textureKey : missingKeys) {
                bakeTask.updateStage(BakeStage.BAKING_PART, textureKey);
                byte[] bakedImage = this.playerSkinBaker.bake(
                    sourceImage,
                    textureKey.skinPart(),
                    textureKey.skinSegment(),
                    source.slimModel()
                );
                String contentHash = MineSkinCache.createContentKey(bakedImage, source.slimModel());
                String textureUrl = resolveTextureUrl(currentApiKey, contentHash, bakedImage, source.slimModel());
                if (textureUrl == null) {
                    continue;
                }
                saved.put(textureKey, textureUrl);
                this.cache.save(source.textureHash(), source.slimModel(), saved);
                bakeTask.markCompleted(textureKey);
                savedAny = true;
            }
            if (savedAny) {
                Emote.LOGGER.info("Saved MineSkin bake for {} ({})", source.playerName(), source.textureHash());
                for (UUID playerUuid : bakeTask.subscribers()) {
                    this.readyNotifier.accept(playerUuid);
                }
            }
            bakeTask.complete();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            bakeTask.cancel();
            Emote.LOGGER.warn("MineSkin bake interrupted for {}", source.playerName(), exception);
        } catch (IOException | IllegalArgumentException exception) {
            bakeTask.fail(exception.getMessage());
            Emote.LOGGER.warn("MineSkin bake failed for {}", source.playerName(), exception);
        }
    }

    private String resolveTextureUrl(
        String currentApiKey,
        String contentHash,
        byte[] bakedImage,
        boolean slimModel
    ) throws IOException, InterruptedException {
        String cachedTextureUrl = this.cache.loadContent(contentHash);
        if (cachedTextureUrl != null) {
            return cachedTextureUrl;
        }

        long now = System.currentTimeMillis();
        if (this.cache.isRetryBlocked(contentHash, now)) {
            return null;
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
            this.cache.saveFailure(contentHash, exception.getMessage(), now + RATE_LIMIT_RETRY_DELAY_MILLIS);
            Emote.LOGGER.warn(
                "MineSkin generation rate limit reached for baked texture {}. "
                    + "Wait for capacity or upgrade the plan assigned to the configured API key: {}",
                contentHash,
                exception.getMessage()
            );
            return null;
        } catch (MineSkinClient.JobFailedException exception) {
            this.cache.clearPendingJob(contentHash);
            if (exception.isRateLimited()) {
                this.cache.saveFailure(contentHash, exception.getMessage(), now + RATE_LIMIT_RETRY_DELAY_MILLIS);
                Emote.LOGGER.warn(
                    "MineSkin generation rate limit reached for baked texture {}. "
                        + "Wait for capacity or upgrade the plan assigned to the configured API key: {}",
                    contentHash,
                    exception.getMessage()
                );
            } else {
                this.cache.saveFailure(contentHash, exception.getMessage(), now + FAILED_JOB_RETRY_DELAY_MILLIS);
                Emote.LOGGER.warn("MineSkin rejected baked texture {}: {}", contentHash, exception.getMessage());
            }
            return null;
        }

        this.cache.saveContent(contentHash, textureUrl);
        this.cache.clearPendingJob(contentHash);
        this.cache.clearFailure(contentHash);
        return textureUrl;
    }

    static final class GenerationQueue {
        private final Supplier<ExecutorService> executorFactory;
        private final Map<String, PendingTask> pendingTasks = new HashMap<>();
        private ExecutorService executor;

        GenerationQueue() {
            this(GenerationQueue::createExecutor);
        }

        GenerationQueue(Supplier<ExecutorService> executorFactory) {
            this.executorFactory = executorFactory;
        }

        synchronized boolean submit(String key, Runnable task) {
            PendingTask pendingTask = this.pendingTasks.get(key);
            if (pendingTask != null) {
                pendingTask.requestRerun();
                return false;
            }
            PendingTask newTask = new PendingTask(task);
            this.pendingTasks.put(key, newTask);
            try {
                executor().execute(() -> {
                    while (true) {
                        newTask.task().run();
                        synchronized (GenerationQueue.this) {
                            if (newTask.takeRerunRequest()) {
                                continue;
                            }
                            pendingTasks.remove(key, newTask);
                            return;
                        }
                    }
                });
                return true;
            } catch (RejectedExecutionException exception) {
                this.pendingTasks.remove(key, newTask);
                throw exception;
            }
        }

        synchronized void cancelAll() {
            this.pendingTasks.clear();
            if (this.executor == null) {
                return;
            }
            this.executor.shutdownNow();
            this.executor = null;
        }

        private synchronized ExecutorService executor() {
            if (this.executor == null) {
                this.executor = this.executorFactory.get();
            }
            return this.executor;
        }

        private static ExecutorService createExecutor() {
            return Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "emote-mineskin");
                thread.setDaemon(true);
                return thread;
            });
        }

        private static final class PendingTask {
            private final Runnable task;
            private boolean rerunRequested;

            private PendingTask(Runnable task) {
                this.task = task;
            }

            private Runnable task() {
                return this.task;
            }

            private void requestRerun() {
                this.rerunRequested = true;
            }

            private boolean takeRerunRequest() {
                boolean requested = this.rerunRequested;
                this.rerunRequested = false;
                return requested;
            }
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

    private static final class BakeTask {
        private final PlayerSkinManager.PlayerSkinSource source;
        private final Set<UUID> subscribers = new LinkedHashSet<>();
        private final Set<PlayerSkinTextureKey> requiredKeys = new LinkedHashSet<>();
        private final Set<PlayerSkinTextureKey> completedKeys = new LinkedHashSet<>();
        private BakeStage stage = BakeStage.QUEUED;
        private PlayerSkinTextureKey currentKey;
        private String errorMessage;

        private BakeTask(PlayerSkinManager.PlayerSkinSource source) {
            this.source = source;
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
                this.errorMessage = null;
            }
            return changed;
        }

        private synchronized Set<PlayerSkinTextureKey> requiredKeys() {
            return Set.copyOf(this.requiredKeys);
        }

        private synchronized Set<UUID> subscribers() {
            return Set.copyOf(this.subscribers);
        }

        private synchronized void updateStage(BakeStage stage, PlayerSkinTextureKey currentKey) {
            this.stage = stage;
            this.currentKey = currentKey;
        }

        private synchronized void markCompleted(PlayerSkinTextureKey textureKey) {
            this.completedKeys.add(textureKey);
        }

        private synchronized void complete() {
            this.stage = BakeStage.COMPLETE;
            this.currentKey = null;
        }

        private synchronized void fail(String errorMessage) {
            this.stage = BakeStage.FAILED;
            this.currentKey = null;
            this.errorMessage = errorMessage;
        }

        private synchronized void cancel() {
            this.stage = BakeStage.CANCELLED;
            this.currentKey = null;
        }
    }
}
