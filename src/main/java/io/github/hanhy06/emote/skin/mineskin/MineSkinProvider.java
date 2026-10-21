package io.github.hanhy06.emote.skin.mineskin;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.config.Config;
import io.github.hanhy06.emote.skin.SkinCache;

import java.io.IOException;
import java.util.Objects;

public final class MineSkinProvider {
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

    public void configure(Config config) {
        this.apiKey = config.mineSkinApiKey();
        this.client.setJobPollIntervalSeconds(config.mineSkinPollIntervalSeconds());
    }

    public boolean available() {
        return MineSkinClient.hasApiKey(this.apiKey);
    }

    public String upload(String contentHash, byte[] png, boolean slimModel) throws IOException, InterruptedException {
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
        long nowEpochMillis = System.currentTimeMillis();
        SkinCache.Failure failure = this.cache.loadFailure(contentHash, nowEpochMillis);
        if (failure != null) {
            return TextureResolution.retry(failure.retryAfterEpochMillis());
        }
        SkinCache.PendingJob pendingJob = this.cache.loadPendingJob(contentHash);
        if (pendingJob != null
            && nowEpochMillis - pendingJob.submittedAtEpochMillis() > SkinCache.PENDING_JOB_MAX_AGE_MILLIS) {
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
            long retryAtEpochMillis = nowEpochMillis + positiveOrRateLimitFallback(exception.retryDelayMillis());
            this.cache.saveFailure(contentHash, exception.getMessage(), retryAtEpochMillis);
            return TextureResolution.retry(retryAtEpochMillis);
        } catch (MineSkinClient.JobFailedException exception) {
            this.cache.clearPendingJob(contentHash);
            if (exception.isRateLimited()) {
                long retryAtEpochMillis = nowEpochMillis + positiveOrRateLimitFallback(exception.retryDelayMillis());
                this.cache.saveFailure(contentHash, exception.getMessage(), retryAtEpochMillis);
                return TextureResolution.retry(retryAtEpochMillis);
            }

            this.cache.saveFailure(contentHash, exception.getMessage(), nowEpochMillis + FAILED_JOB_RETRY_DELAY_MILLIS);
            EmoteMod.LOGGER.warn("MineSkin rejected baked texture {}: {}", contentHash, exception.getMessage());
            return TextureResolution.failed();
        }

        return TextureResolution.ready(textureUrl);
    }

    private static long positiveOrRateLimitFallback(long value) {
        return value > 0L ? value : RATE_LIMIT_RETRY_DELAY_MILLIS;
    }

    private record TextureResolution(String textureUrl, long retryAtEpochMillis) {
        private static TextureResolution ready(String textureUrl) {
            return new TextureResolution(Objects.requireNonNull(textureUrl, "textureUrl"), 0L);
        }

        private static TextureResolution retry(long retryAtEpochMillis) {
            return new TextureResolution(null, retryAtEpochMillis);
        }

        private static TextureResolution failed() {
            return new TextureResolution(null, 0L);
        }
    }
}
