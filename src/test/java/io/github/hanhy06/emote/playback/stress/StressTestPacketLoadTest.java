package io.github.hanhy06.emote.playback.stress;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class StressTestPacketLoadTest {
    private static ProtocolInfo<ClientGamePacketListener> protocol;
    private EventLoopGroup eventLoops;

    @BeforeAll
    static void initializeMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        protocol = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY));
    }

    @BeforeEach
    void startEventLoops() {
        this.eventLoops = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
    }

    @AfterEach
    void stopEventLoops() {
        this.eventLoops.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 256, 0})
    void accountsForFanoutBundlesCompressionAndTickBoundaries(int compressionThreshold) {
        var session = new StressTestPacketLoad.Session(protocol, compressionThreshold, 4, this.eventLoops);
        try {
            session.sendForAll(new ClientboundBundlePacket(List.of(
                new ClientboundRemoveEntitiesPacket(1), new ClientboundRemoveEntitiesPacket(2)
            )));
            session.beginRuntime();

            int[] ids = new int[1_024];
            Arrays.fill(ids, 42);
            var packet = new ClientboundRemoveEntitiesPacket(ids);
            for (int updates : new int[]{8, 2, 5}) {
                for (int index = 0; index < updates; index++) session.sendForAll(packet);
                session.sampleRuntimeTick();
            }
            var result = session.createResult();
            assertEquals(4, result.packetFanout());
            assertEquals(16, result.creationPackets());
            assertTrue(result.creationBytes() > 0);
            assertEquals(60, result.runtimePackets());
            assertEquals(3, result.runtimeSamples());
            assertEquals(result.runtimeBytes() / 15 * 8, result.maximumRuntimeBytesPerTick());
            assertTrue(result.runtimeProcessingNanos() > 0);
            assertTrue(result.maximumRuntimeProcessingNanosPerTick() > 0);
            assertTrue(result.maximumRuntimeProcessingNanosPerTick() <= result.runtimeProcessingNanos());

            ByteBuf encoded = Unpooled.buffer();
            try {
                protocol.codec().encode(encoded, packet);
                long uncompressedBytes = 60L * (encoded.readableBytes() + VarInt.getByteSize(encoded.readableBytes()));
                if (compressionThreshold < 0) assertEquals(uncompressedBytes, result.runtimeBytes());
                else assertTrue(result.runtimeBytes() > 0 && result.runtimeBytes() < uncompressedBytes);
            } finally {
                encoded.release();
            }
        } finally {
            session.close();
        }
    }

    @Test
    void submitsWithoutWaitingForNetworkThreadsAndDrainsBeforeReporting() throws Exception {
        var session = new StressTestPacketLoad.Session(protocol, -1, 3, this.eventLoops);
        CountDownLatch release = new CountDownLatch(1);
        try {
            session.beginRuntime();
            CountDownLatch blocked = new CountDownLatch(2);
            for (var eventLoop : this.eventLoops) {
                eventLoop.execute(() -> {
                    blocked.countDown();
                    try { release.await(10, TimeUnit.SECONDS); }
                    catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                });
            }
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            CompletableFuture.runAsync(() -> {
                for (int index = 0; index < 64; index++) session.sendForAll(new ClientboundRemoveEntitiesPacket(index));
                session.sampleRuntimeTick();
            }).get(5, TimeUnit.SECONDS);

            CountDownLatch reporting = new CountDownLatch(1);
            var result = CompletableFuture.supplyAsync(() -> {
                reporting.countDown();
                return session.createResult();
            });
            assertTrue(reporting.await(5, TimeUnit.SECONDS));
            assertFalse(result.isDone());
            release.countDown();
            var packets = result.get(5, TimeUnit.SECONDS);
            assertEquals(192, packets.runtimePackets());
            assertEquals(0, packets.creationPackets());
            assertEquals(1, packets.runtimeSamples());
            assertEquals(packets.runtimeBytes(), packets.maximumRuntimeBytesPerTick());
        } finally {
            release.countDown();
            session.close();
        }
    }

    @Test
    void reportsAsynchronousEncodingFailureAndClosesChannels() {
        var session = new StressTestPacketLoad.Session(protocol, -1, 2, this.eventLoops);
        try {
            session.beginRuntime();
            session.sendForAll(new ServerboundAcceptTeleportationPacket(1, 0, 0, 0, 0, 0));
            assertThrows(IllegalStateException.class, session::createResult);
        } finally {
            session.close();
        }
    }
}
