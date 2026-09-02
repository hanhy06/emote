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
    double averageEmoteProcessingMillis,
    double maximumEmoteProcessingMillis,
    double networkProcessingSeconds,
    double averageNetworkProcessingMillis,
    double maximumNetworkProcessingMillis,
    StressTestPacketLoad.PacketLoadResult packetLoad
) {
    public double runtimeSeconds() {
        return Math.max(0.0D, this.elapsedSeconds - this.creationMillis / 1_000.0D - this.cleanupMillis / 1_000.0D);
    }

    public double observedTps() {
        double runtimeSeconds = runtimeSeconds();
        return runtimeSeconds == 0.0D ? 0.0D : this.completedTicks / runtimeSeconds;
    }
}
