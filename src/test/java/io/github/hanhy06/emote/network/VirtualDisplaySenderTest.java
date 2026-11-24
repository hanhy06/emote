package io.github.hanhy06.emote.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.PacketBundleUnpacker;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.Varint21LengthFieldPrepender;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.Bootstrap;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class VirtualDisplaySenderTest {
    @Test
    void sharedFramesMatchVanillaWithoutRepeatingEncodingOrCompression() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var protocol = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY));
        var shortPacket = new ClientboundSetEntityDataPacket(130, List.of(
            new SynchedEntityData.DataValue<>(8, EntityDataSerializers.INT, 0),
            new SynchedEntityData.DataValue<>(11, EntityDataSerializers.VECTOR3, new Vector3f(0.5F, -1F, 4F)),
            new SynchedEntityData.DataValue<>(13, EntityDataSerializers.QUATERNION, new Quaternionf().rotateY(0.4F))
        ));
        var mediumPacket = new ClientboundSetEntityDataPacket(16384, java.util.stream.IntStream.range(0, 10)
            .<SynchedEntityData.DataValue<?>>mapToObj(id -> new SynchedEntityData.DataValue<>(id, EntityDataSerializers.VECTOR3, new Vector3f(id)))
            .toList());
        var longPacket = new ClientboundSetEntityDataPacket(130, List.of(
            new SynchedEntityData.DataValue<>(23, EntityDataSerializers.COMPONENT, Component.literal("display metadata ".repeat(64)))
        ));
        var packets = List.<Packet<?>>of(shortPacket, mediumPacket, longPacket, new ClientboundBundlePacket(List.of(shortPacket, longPacket)));

        var displaySender = new VirtualDisplaySender();
        displaySender.startEncoder(protocol);
        try {
            var sharedPackets = packets.stream().map(displaySender::packet).toList();
            var contextualPacket = new VirtualDisplaySender.DisplayPacket(longPacket, false, 0);
            assertNull(displaySender.frame(contextualPacket, 0));
            assertEquals(0, displaySender.stats().codecCalls());
            assertEquals(0, displaySender.stats().compressionCalls());
            for (int threshold : new int[]{-1, 0, 256}) {
                var before = displaySender.stats();
                var handlers = new ArrayList<ChannelHandler>();
                handlers.add(new Varint21LengthFieldPrepender());
                if (threshold >= 0) handlers.add(new CompressionEncoder(threshold));
                handlers.add(new PacketEncoder<>(protocol));
                handlers.add(new PacketBundleUnpacker(protocol.bundlerInfo()));
                var vanilla = new EmbeddedChannel(handlers.toArray(ChannelHandler[]::new));
                try {
                    for (int i = 0; i < packets.size(); i++) {
                        var packet = packets.get(i);
                        var sharedPacket = sharedPackets.get(i);
                        var frame = displaySender.frame(sharedPacket, threshold);
                        assertSame(frame, displaySender.frame(sharedPacket, threshold));
                        vanilla.writeOutbound(packet);
                        ByteArrayOutputStream expected = new ByteArrayOutputStream();
                        ByteBuf part;
                        while ((part = vanilla.readOutbound()) != null) {
                            expected.writeBytes(ByteBufUtil.getBytes(part));
                            part.release();
                        }
                        assertArrayEquals(expected.toByteArray(), frame.bytes(), "threshold " + threshold + ", " + packet.type());
                    }
                } finally {
                    vanilla.finishAndReleaseAll();
                }
                // Three individual packets and a bundle containing two packets and two delimiters.
                var stats = displaySender.stats().since(before);
                assertEquals(threshold == -1 ? 7 : 0, stats.codecCalls(), "threshold " + threshold);
                assertEquals(threshold == 0 ? 7 : 0, stats.compressionCalls(), "threshold " + threshold);
                assertEquals(0, stats.failedEncodes());
            }
        } finally {
            displaySender.stop();
        }
    }
}
