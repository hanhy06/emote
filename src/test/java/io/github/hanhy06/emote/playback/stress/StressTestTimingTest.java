package io.github.hanhy06.emote.playback.stress;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import static io.github.hanhy06.emote.playback.stress.StressTestTiming.Phase.*;
import static io.github.hanhy06.emote.playback.stress.StressTestTiming.ThreadRole.*;
import static org.junit.jupiter.api.Assertions.*;

class StressTestTimingTest {
    @Test
    void reportsOverlappingThreadWorkWithoutDuplicatingWallTime() {
        AtomicLong clock = new AtomicLong();
        StressTestTiming timing = new StressTestTiming(clock::get);
        timing.begin(SETUP);
        clock.set(1_000_000_000L);
        timing.end(SETUP);
        clock.set(2_000_000_000L);
        timing.begin(MAIN);
        clock.set(3_000_000_000L);
        timing.begin(WORKER);
        clock.set(4_000_000_000L);
        timing.end(MAIN);
        clock.set(5_000_000_000L);
        timing.end(WORKER);
        clock.set(7_000_000_000L);
        timing.begin(CLEANUP);
        clock.set(8_000_000_000L);
        timing.end(CLEANUP);
        clock.set(9_000_000_000L);
        long[] durations = timing.finish();
        var report = new PlaybackStressTestReport(
            1, 1, 1, 0, 140, Arrays.stream(durations).sum() / 1_000_000_000.0D,
            durations[SETUP.ordinal()] / 1_000_000.0D, durations[CLEANUP.ordinal()] / 1_000_000.0D,
            0, 0, 0, 0, durations[EMOTE.ordinal()] / 1_000_000_000.0D, 0, 0, 0, 0
        );

        assertEquals(2_000_000_000L, timing.nanos(MAIN));
        assertEquals(2_000_000_000L, timing.nanos(WORKER));
        assertArrayEquals(new long[]{10, 30, 40, 10}, report.durationTenths());
    }
}
