package io.github.hanhy06.emote.playback.stress;

public record PlaybackStressTestReport(
    int requestedInstances,
    int activeInstances,
    int peakDisplayEntities,
    int failedInstances,
    int completedTicks,
    double elapsedSeconds,
    double creationMillis,
    double cleanupMillis,
    double baselineMspt,
    double averageMspt,
    double percentile95Mspt,
    double maximumMspt,
    double emoteProcessingSeconds,
    double networkProcessingSeconds,
    double averageMainProcessingMillis,
    double maximumMainProcessingMillis,
    double averageWorkerProcessingMillis,
    double maximumWorkerProcessingMillis
) {
    public long[] durationTenths() {
        double setupSeconds = this.creationMillis / 1_000.0D;
        double cleanupSeconds = this.cleanupMillis / 1_000.0D;
        double serverAndIdleSeconds = Math.max(0.0D, this.elapsedSeconds - setupSeconds - this.emoteProcessingSeconds - this.networkProcessingSeconds - cleanupSeconds);
        double[] seconds = {setupSeconds, this.emoteProcessingSeconds, this.networkProcessingSeconds, serverAndIdleSeconds, cleanupSeconds};
        long[] tenths = new long[seconds.length];
        long total = Math.round(this.elapsedSeconds * 10.0D);
        long previous = 0;
        double cumulative = 0.0D;
        for (int index = 0; index < seconds.length; index++) {
            cumulative += seconds[index];
            long boundary = index == seconds.length - 1 ? total : Math.clamp(Math.round(cumulative * 10.0D), previous, total);
            tenths[index] = boundary - previous;
            previous = boundary;
        }
        return tenths;
    }

    public double runtimeSeconds() {
        return Math.max(0.0D, this.elapsedSeconds - this.creationMillis / 1_000.0D - this.cleanupMillis / 1_000.0D);
    }

    public double observedTps() {
        double runtimeSeconds = runtimeSeconds();
        return runtimeSeconds == 0.0D ? 0.0D : this.completedTicks / runtimeSeconds;
    }
}
