package io.github.hanhy06.emote.playback.stress;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import org.jspecify.annotations.Nullable;

import java.util.function.LongSupplier;

public final class StressTestTiming {
    public static volatile @Nullable StressTestTiming INSTANCE;

    // Earlier phases own overlapping wall time; parallel work is never added twice.
    public enum Phase { SETUP, CLEANUP, EMOTE, NETWORK, SERVER_IDLE }

    private final LongSupplier clock;
    private final int[] active = new int[Phase.values().length];
    private final long[] durations = new long[Phase.values().length];
    private long lastNanos;
    private boolean finished;

    public StressTestTiming() { this(System::nanoTime); }

    StressTestTiming(LongSupplier clock) {
        this.clock = clock;
        this.lastNanos = clock.getAsLong();
    }

    public synchronized void begin(Phase phase) {
        if (this.finished) return;
        update(this.clock.getAsLong());
        this.active[phase.ordinal()]++;
    }

    public synchronized void end(Phase phase) {
        if (this.finished) return;
        update(this.clock.getAsLong());
        this.active[phase.ordinal()]--;
    }

    public synchronized long nanos(Phase phase) {
        if (!this.finished) update(this.clock.getAsLong());
        return this.durations[phase.ordinal()];
    }

    public synchronized long[] finish() {
        if (!this.finished) {
            update(this.clock.getAsLong());
            this.finished = true;
            if (INSTANCE == this) INSTANCE = null;
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
        this.lastNanos = now;
    }

    public static final class NetworkHandler extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
            StressTestTiming timing = INSTANCE;
            if (timing == null) {
                context.write(message, promise);
                return;
            }
            timing.begin(Phase.NETWORK);
            try { context.write(message, promise); }
            finally { timing.end(Phase.NETWORK); }
        }

        @Override
        public void flush(ChannelHandlerContext context) throws Exception {
            StressTestTiming timing = INSTANCE;
            if (timing == null) {
                context.flush();
                return;
            }
            timing.begin(Phase.NETWORK);
            try { context.flush(); }
            finally { timing.end(Phase.NETWORK); }
        }
    }
}
