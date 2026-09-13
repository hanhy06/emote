package io.github.hanhy06.emote.skin.mineskin;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.config.Config;
import io.github.hanhy06.emote.skin.SkinBakeCoordinator;
import io.github.hanhy06.emote.skin.SkinCache;

import java.io.IOException;
import java.util.Objects;

public final class MineSkinProvider implements SkinBakeCoordinator.FallbackUploader {
    private static final long FAILED_JOB_RETRY_DELAY_MILLIS = 5L * 60L * 1000L;
    private static final long RATE_LIMIT_RETRY_DELAY_MILLIS = 2L * 60L * 1000L;
    private static final int RATE_LIMIT_RETRY_LIMIT = 3;

    private final SkinCache cache;
    private final MineSkinClient client;
    private volatile String apiKey = "";

    public MineSkinProvider(SkinCache cache, MineSkinClient client) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public void configure(Config config) {
        this.apiKey = config.mineSkinApiKey();
        this.client.setJobPollIntervalSeconds(config.mineSkinPollIntervalSeconds());
    }

    @Override
    public boolean available() {
        return MineSkinClient.hasApiKey(this.apiKey);
    }

    @Override
    public String upload(byte[] png, boolean slimModel) throws IOException, InterruptedException {
        String contentHash = SkinCache.createContentKey(png, slimModel);
        for (int attempt = 0; attempt <= RATE_LIMIT_RETRY_LIMIT; attempt++) {
            TextureResolution resolution = resolveTextureUrl(this.apiKey, contentHash, png, slimModel);
            if (resolution.textureUrl() != null) {
                return resolution.textureUrl();
            }
            if (resolution.retryAtEpochMillis() <= 0L || attempt == RATE_LIMIT_RETRY_LIMIT) {
                break;
            }
            Thread.sleep(Math.max(1L, resolution.retryAtEpochMillis() - System.currentTimeMillis()));
        }
        throw new IOException("MineSkin could not generate the skin texture");
    }

    private synchronized TextureResolution resolveTextureUrl(
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
        SkinCache.Failure failure = this.cache.loadFailure(contentHash, now);
        if (failure != null) {
            return TextureResolution.retry(failure.retryAfterEpochMillis(), failure.errorMessage());
        }
        SkinCache.PendingJob pendingJob = this.cache.loadPendingJob(contentHash);
        if (pendingJob != null
            && now - pendingJob.submittedAtEpochMillis() > SkinCache.PENDING_JOB_MAX_AGE_MILLIS) {
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
            }

            this.cache.saveFailure(contentHash, exception.getMessage(), now + FAILED_JOB_RETRY_DELAY_MILLIS);
            EmoteMod.LOGGER.warn("MineSkin rejected baked texture {}: {}", contentHash, exception.getMessage());
            return TextureResolution.failed(exception.getMessage());
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
