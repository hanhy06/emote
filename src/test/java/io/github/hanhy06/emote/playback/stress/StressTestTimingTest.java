package io.github.hanhy06.emote.playback.stress;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static io.github.hanhy06.emote.playback.stress.StressTestTiming.Phase.*;
import static io.github.hanhy06.emote.playback.stress.StressTestTiming.ThreadRole.*;
import static org.junit.jupiter.api.Assertions.*;

class StressTestTimingTest {
    @Test
    void partitionsOverlappingWorkerAndNetworkTimeAndClipsOpenWorkAtStop() {
        AtomicLong clock = new AtomicLong();
        StressTestTiming timing = new StressTestTiming(clock::get);
        timing.begin(SETUP);
        clock.set(10);
        timing.begin(NETWORK);
        clock.set(20);
        timing.end(SETUP);
        clock.set(30);
        timing.end(NETWORK);
        clock.set(40);
        timing.begin(MAIN);
        clock.set(45);
        timing.begin(WORKER);
        clock.set(50);
        timing.begin(NETWORK);
        clock.set(60);
        timing.end(MAIN);
        clock.set(70);
        timing.end(WORKER);
        clock.set(80);
        timing.end(NETWORK);
        clock.set(90);
        timing.begin(CLEANUP);
        clock.set(95);
        timing.begin(WORKER);
        clock.set(100);
        long[] durations = timing.finish();
        assertArrayEquals(new long[]{20, 10, 30, 20, 20}, durations);
        assertEquals(100, Arrays.stream(durations).sum());
        assertEquals(20, timing.nanos(MAIN));
        assertEquals(30, timing.nanos(WORKER));
        clock.set(200);
        timing.end(CLEANUP);
        timing.end(WORKER);
        assertArrayEquals(durations, timing.finish());
        assertEquals(30, timing.nanos(WORKER));
    }

    @Test
    void measuresOutboundWriteAndFlushAndPreservesThePayload() {
        AtomicLong clock = new AtomicLong();
        StressTestTiming timing = new StressTestTiming(clock::get);
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                clock.addAndGet(3);
                context.write(message, promise);
            }

            @Override
            public void flush(ChannelHandlerContext context) {
                clock.addAndGet(2);
                context.flush();
            }
        }, new StressTestTiming.NetworkHandler());
        StressTestTiming.INSTANCE = timing;
        try {
            var payload = Unpooled.buffer().writeInt(123);
            assertTrue(channel.writeOutbound(payload));
            assertSame(payload, channel.readOutbound());
            payload.release();
            clock.addAndGet(10);
            assertArrayEquals(new long[]{0, 0, 0, 5, 10}, timing.finish());
            assertNull(StressTestTiming.INSTANCE);
            assertTrue(channel.writeOutbound("after stop"));
            assertEquals("after stop", channel.readOutbound());
            assertArrayEquals(new long[]{0, 0, 0, 5, 10}, timing.finish());
        } finally {
            timing.finish();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void failedWriteDoesNotLeaveNetworkTimingActive() {
        AtomicLong clock = new AtomicLong();
        StressTestTiming timing = new StressTestTiming(clock::get);
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                clock.set(7);
                promise.setFailure(new IllegalStateException("write failed"));
            }
        }, new StressTestTiming.NetworkHandler());
        StressTestTiming.INSTANCE = timing;
        try {
            assertThrows(IllegalStateException.class, () -> channel.writeOutbound("packet"));
            clock.set(20);
            assertArrayEquals(new long[]{0, 0, 0, 7, 13}, timing.finish());
        } finally {
            timing.finish();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void roundedBreakdownAlwaysAddsUpToTheDisplayedTotal() {
        Random random = new Random(123);
        for (int index = 0; index < 1_000; index++) {
            double setup = random.nextDouble();
            double emote = random.nextDouble();
            double network = random.nextDouble();
            double idle = random.nextDouble();
            double cleanup = random.nextDouble();
            double elapsed = setup + emote + network + idle + cleanup;
            PlaybackStressTestReport report = new PlaybackStressTestReport(
                1, 1, 1, 0, 200, elapsed, setup * 1_000, cleanup * 1_000,
                0, 0, 0, 0, emote, network, 0, 0, 0, 0
            );
            long[] tenths = report.durationTenths();
            assertEquals(Math.round(elapsed * 10), Arrays.stream(tenths).sum());
            double[] seconds = {setup, emote, network, idle, cleanup};
            for (int phase = 0; phase < tenths.length; phase++) {
                assertTrue(tenths[phase] >= 0);
                assertEquals(seconds[phase], tenths[phase] / 10.0D, 0.100000001);
            }
        }
    }
}
