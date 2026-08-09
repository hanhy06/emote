package io.github.hanhy06.emote.playback;

public record PlaybackStressTestReport(
    int requestedInstances,
    int activeInstances,
    int peakDisplayEntities,
    int failedInstances,
    int measuredServerTicks,
    double elapsedSeconds,
    double creationMillis,
    double cleanupMillis,
    double baselineMspt,
    double averageMspt,
    double maximumMspt,
    double baselineTps,
    double averageTps,
    double minimumTps,
    double tpsDrop,
    double averageManagerCpuMillis,
    double maximumManagerCpuMillis
) {
}
