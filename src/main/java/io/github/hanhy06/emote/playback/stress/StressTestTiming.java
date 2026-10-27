package io.github.hanhy06.emote.playback.stress;

import java.util.function.LongSupplier;

final class StressTestTiming {
    // Earlier phases own overlapping wall time; parallel work is never added twice.
    enum Phase { SETUP, CLEANUP, EMOTE, SERVER_IDLE }
    enum ThreadRole { MAIN, WORKER }

    private final LongSupplier clock;
    private final int[] active = new int[Phase.values().length];
    private final long[] durations = new long[Phase.values().length];
    private final int[] activeThreads = new int[ThreadRole.values().length];
    private final long[] threadNanos = new long[ThreadRole.values().length];
    private long lastNanos;
    private boolean finished;

    StressTestTiming() { this(System::nanoTime); }

    StressTestTiming(LongSupplier clock) {
        this.clock = clock;
        this.lastNanos = clock.getAsLong();
    }

    synchronized void begin(Phase phase) {
        if (this.finished) return;
        update(this.clock.getAsLong());
        this.active[phase.ordinal()]++;
    }

    synchronized void end(Phase phase) {
        if (this.finished) return;
        update(this.clock.getAsLong());
        this.active[phase.ordinal()]--;
    }

    synchronized void begin(ThreadRole thread) {
        if (this.finished) return;
        update(this.clock.getAsLong());
        this.active[Phase.EMOTE.ordinal()]++;
        this.activeThreads[thread.ordinal()]++;
    }

    synchronized void end(ThreadRole thread) {
        if (this.finished) return;
        update(this.clock.getAsLong());
        this.active[Phase.EMOTE.ordinal()]--;
        this.activeThreads[thread.ordinal()]--;
    }

    synchronized long nanos(ThreadRole thread) {
        if (!this.finished) update(this.clock.getAsLong());
        return this.threadNanos[thread.ordinal()];
    }

    synchronized long[] finish() {
        if (!this.finished) {
            update(this.clock.getAsLong());
            this.finished = true;
        }
        return this.durations.clone();
    }

    private void update(long now) {
        int phase = Phase.SERVER_IDLE.ordinal();
        for (int index = 0; index < phase; index++) {
            if (this.active[index] > 0) {
                phase = index;
                break;
            }
        }
        this.durations[phase] += now - this.lastNanos;
        for (int index = 0; index < this.activeThreads.length; index++) {
            if (this.activeThreads[index] > 0) this.threadNanos[index] += now - this.lastNanos;
        }
        this.lastNanos = now;
    }
}
