package io.github.hanhy06.emote.skin;

public record SkinProcessingStats(
    String provider,
    int activeJobs,
    int queuedJobs,
    int retryingJobs
) {
    public static SkinProcessingStats unavailable() {
        return new SkinProcessingStats("Unavailable", 0, 0, 0);
    }
}
