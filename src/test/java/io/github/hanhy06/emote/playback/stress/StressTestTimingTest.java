package io.github.hanhy06.emote.playback.stress;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class StressTestTimingTest {
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

}
