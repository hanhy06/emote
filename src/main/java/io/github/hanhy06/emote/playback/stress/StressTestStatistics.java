package io.github.hanhy06.emote.playback.stress;

import java.util.ArrayList;
import java.util.List;

final class StressTestStatistics {
    private StressTestStatistics() {
    }

    static long median(List<Long> samples) {
        if (samples.isEmpty()) {
            return 0L;
        }

        List<Long> sorted = new ArrayList<>(samples);
        sorted.sort(Long::compareTo);
        int middle = sorted.size() / 2;
        if ((sorted.size() & 1) == 1) {
            return sorted.get(middle);
        }
        return Math.round((sorted.get(middle - 1) / 2.0D) + (sorted.get(middle) / 2.0D));
    }

    static long percentile95(List<Long> samples) {
        if (samples.isEmpty()) {
            return 0L;
        }

        List<Long> sorted = new ArrayList<>(samples);
        sorted.sort(Long::compareTo);
        int index = Math.max(0, (int) Math.ceil(sorted.size() * 0.95D) - 1);
        return sorted.get(index);
    }
}
