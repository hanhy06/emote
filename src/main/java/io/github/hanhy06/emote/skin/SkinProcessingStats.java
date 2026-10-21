package io.github.hanhy06.emote.skin;

public record SkinProcessingStats(
    String provider,
    int activeJobs,
    int queuedJobs,
    int retryingJobs
) {
}
