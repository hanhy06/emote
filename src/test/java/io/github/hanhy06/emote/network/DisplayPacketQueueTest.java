package io.github.hanhy06.emote.network;

import io.github.hanhy06.emote.network.VirtualDisplaySender.DisplayPacket;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayPacketQueueTest {
    @Test
    void preservesDirtyFieldsAndContentOrderAcrossBackpressure() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var queue = new DisplayPacketQueue();
        int id = 10;
        int otherId = 20;
        var olderMove = move(id, 1);
        var latestMove = move(id, 2);
        var first = metadata(id, field(11, 3), field(12, 5));
        var latest = metadata(id, field(11, 4));
        var content = metadata(id, field(17, 100));
        var remove = new ClientboundRemoveEntitiesPacket(id);
        var other = metadata(otherId, field(11, 7));

        queue.addAll(List.of(pose(first), pose(olderMove), pose(latest), pose(latestMove),
            packet(content, 0), pose(metadata(id, field(11, 6))), pose(other), packet(remove, 0)));
        var drained = queue.drain(DisplayPacketQueueTest::pose).stream().map(DisplayPacket::packet).toList();
        assertEquals(List.of(metadata(id, field(11, 4), field(12, 5)), latestMove, content, remove, other), drained);
        assertTrue(queue.isEmpty());
    }

    private static ClientboundEntityPositionSyncPacket move(int entityId, int x) {
        return new ClientboundEntityPositionSyncPacket(entityId, PositionPath.of(new Vec3(x, 0, 0)), 0, 0, 0, false);
    }

    private static ClientboundSetEntityDataPacket metadata(int entityId, SynchedEntityData.DataValue<?>... values) {
        return new ClientboundSetEntityDataPacket(entityId, List.of(values));
    }

    private static SynchedEntityData.DataValue<Integer> field(int id, int value) {
        return new SynchedEntityData.DataValue<>(id, EntityDataSerializers.INT, value);
    }

    private static DisplayPacket pose(ClientboundSetEntityDataPacket packet) {
        return packet(packet, DisplayPacketQueue.poseKey(packet.id(), true));
    }

    private static DisplayPacket pose(ClientboundEntityPositionSyncPacket packet) {
        return packet(packet, DisplayPacketQueue.poseKey(packet.id(), false));
    }

    private static DisplayPacket packet(Packet<?> packet, long key) {
        return new DisplayPacket(packet, false, key);
    }
}
