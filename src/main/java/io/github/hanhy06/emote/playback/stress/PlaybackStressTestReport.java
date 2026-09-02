package io.github.hanhy06.emote.playback.stress;

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
    double medianMspt,
    double percentile95Mspt,
    double maximumMspt,
    double baselineTps,
    double averageTps,
    double medianTps,
    double percentile5Tps,
    double minimumTps,
    double tpsDrop,
    double averageManagerCpuMillis,
    double medianManagerCpuMillis,
    double percentile95ManagerCpuMillis,
    double maximumManagerCpuMillis,
    StressTestPacketLoad.PacketLoadResult packetLoad
) {
}
