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

    private final PlayerSkinBaker playerSkinBaker;
    private final MineSkinCache cache;
    private final MineSkinClient client;
    private final GenerationQueue generationQueue;
    private final Consumer<UUID> readyNotifier;

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
        Set<PlayerSkinTextureKey> requestedKeys = Set.copyOf(requiredKeys);
        this.generationQueue.submit(pendingKey, () -> bakeAndSave(currentApiKey, source, requestedKeys));
    }

    private void bakeAndSave(
        String currentApiKey,
        PlayerSkinManager.PlayerSkinSource source,
        Set<PlayerSkinTextureKey> requiredKeys
    ) {
        try {
            Map<PlayerSkinTextureKey, String> stored = this.cache.load(source.textureHash(), source.slimModel());
            Set<PlayerSkinTextureKey> missingKeys = new LinkedHashSet<>(requiredKeys);
            missingKeys.removeAll(stored.keySet());
            if (missingKeys.isEmpty()) {
                return;
            }

            BufferedImage sourceImage = this.client.downloadSkinImage(source.textureUrl());
            Map<PlayerSkinTextureKey, String> saved = new HashMap<>(stored);
            boolean savedAny = false;
            for (PlayerSkinTextureKey textureKey : missingKeys) {
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
                savedAny = true;
            }
            if (savedAny) {
                Emote.LOGGER.info("Saved MineSkin bake for {} ({})", source.playerName(), source.textureHash());
                this.readyNotifier.accept(source.playerUuid());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            Emote.LOGGER.warn("MineSkin bake interrupted for {}", source.playerName(), exception);
        } catch (IOException | IllegalArgumentException exception) {
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
            this.cache.saveFailure(contentHash, exception.getMessage(), now + FAILED_JOB_RETRY_DELAY_MILLIS);
            Emote.LOGGER.warn(
                "MineSkin generation rate limit reached for baked texture {}. "
                    + "Wait for capacity or upgrade the plan assigned to the configured API key: {}",
                contentHash,
                exception.getMessage()
            );
            return null;
        } catch (MineSkinClient.JobFailedException exception) {
            this.cache.clearPendingJob(contentHash);
            this.cache.saveFailure(contentHash, exception.getMessage(), now + FAILED_JOB_RETRY_DELAY_MILLIS);
            if (exception.isRateLimited()) {
                Emote.LOGGER.warn(
                    "MineSkin generation rate limit reached for baked texture {}. "
                        + "Wait for capacity or upgrade the plan assigned to the configured API key: {}",
                    contentHash,
                    exception.getMessage()
                );
            } else {
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
        private final Map<String, Object> pendingTasks = new HashMap<>();
        private ExecutorService executor;

        GenerationQueue() {
            this(GenerationQueue::createExecutor);
        }

        GenerationQueue(Supplier<ExecutorService> executorFactory) {
            this.executorFactory = executorFactory;
        }

        synchronized boolean submit(String key, Runnable task) {
            if (this.pendingTasks.containsKey(key)) {
                return false;
            }
            Object taskToken = new Object();
            this.pendingTasks.put(key, taskToken);
            try {
                executor().execute(() -> {
                    try {
                        task.run();
                    } finally {
                        removePendingTask(key, taskToken);
                    }
                });
                return true;
            } catch (RejectedExecutionException exception) {
                this.pendingTasks.remove(key, taskToken);
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

        private synchronized void removePendingTask(String key, Object taskToken) {
            this.pendingTasks.remove(key, taskToken);
        }

        private static ExecutorService createExecutor() {
            return Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "emote-mineskin");
                thread.setDaemon(true);
                return thread;
            });
        }
    }
}
