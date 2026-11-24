package io.github.hanhy06.emote.network;

import com.sun.management.OperatingSystemMXBean;
import org.jspecify.annotations.Nullable;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

public record JvmBenchmarkStats(
    @Nullable Long processCpuNanos, @Nullable Long serverThreadCpuNanos, long maxHeapBytes,
    long peakSampledHeapUsedBytes, List<MemorySample> memorySamples, List<GarbageCollection> garbageCollections
) {
    public record MemorySample(double elapsedSeconds, long heapUsedBytes, long heapCommittedBytes, long nonHeapUsedBytes) {}

    public record GarbageCollection(String collector, @Nullable Long collections, @Nullable Long collectionMillis) {}

    static final class Measurement {
        private final long processCpuAtStart = processCpuTime();
        private final long serverCpuAtStart = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
        private final List<GarbageCollection> initialGc = gcSnapshot();
        private final List<MemorySample> memory = new ArrayList<>();
        private long nextSampleNanos;

        void sample(long elapsedNanos, boolean last) {
            if (!last && elapsedNanos < this.nextSampleNanos) return;
            var bean = ManagementFactory.getMemoryMXBean();
            var heap = bean.getHeapMemoryUsage();
            this.memory.add(new MemorySample(elapsedNanos / 1.0e9, heap.getUsed(), heap.getCommitted(), bean.getNonHeapMemoryUsage().getUsed()));
            this.nextSampleNanos = elapsedNanos + 1_000_000_000L;
        }

        JvmBenchmarkStats finish(long elapsedNanos) {
            var processCpu = delta(this.processCpuAtStart, processCpuTime());
            var serverCpu = delta(this.serverCpuAtStart, ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime());
            sample(elapsedNanos, true);
            var finalGc = gcSnapshot();
            var gcDeltas = finalGc.stream().map(end -> this.initialGc.stream()
                .filter(start -> start.collector.equals(end.collector)).findFirst()
                .map(start -> new GarbageCollection(end.collector, delta(start.collections, end.collections), delta(start.collectionMillis, end.collectionMillis)))
                .orElseGet(() -> new GarbageCollection(end.collector, null, null))).toList();
            long peakHeap = this.memory.stream().mapToLong(MemorySample::heapUsedBytes).max().orElse(0);
            return new JvmBenchmarkStats(processCpu, serverCpu, Runtime.getRuntime().maxMemory(), peakHeap, List.copyOf(this.memory), gcDeltas);
        }

        private static List<GarbageCollection> gcSnapshot() {
            return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(gc -> new GarbageCollection(gc.getName(), gc.getCollectionCount(), gc.getCollectionTime())).toList();
        }

        private static long processCpuTime() {
            return ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean os ? os.getProcessCpuTime() : -1;
        }

        private static @Nullable Long delta(long start, long end) {
            return start >= 0 && end >= start ? end - start : null;
        }
    }
}
