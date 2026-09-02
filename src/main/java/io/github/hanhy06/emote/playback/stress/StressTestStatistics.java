package io.github.hanhy06.emote.playback.stress;

import java.util.ArrayList;
import java.util.List;

final class StressTestStatistics {
    private StressTestStatistics() {
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
