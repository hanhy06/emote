package io.github.hanhy06.emote.network;

import io.github.hanhy06.emote.network.VirtualDisplaySender.DisplayPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;

import java.util.*;
import java.util.function.Function;

final class DisplayPacketQueue {
    private final List<QueuedPacket> ordered = new ArrayList<>();
    private final Map<Long, QueuedPacket> poses = new LinkedHashMap<>();
    private long poseOrder;

    boolean isEmpty() {
        return this.ordered.isEmpty() && this.poses.isEmpty();
    }

    void addAll(List<DisplayPacket> packets) {
        for (DisplayPacket packet : packets) {
            long key = packet.poseKey();
            if (key != 0) {
                QueuedPacket previous = this.poses.remove(key);
                if (previous == null) previous = new QueuedPacket(packet);
                else previous.replace(packet);
                previous.order = this.poseOrder++;
                this.poses.put(key, previous);
            } else {
                switch (packet.packet()) {
                    case ClientboundRemoveEntitiesPacket removal -> {
                        for (int id : removal.entityIds()) {
                            this.poses.remove(poseKey(id, false));
                            this.poses.remove(poseKey(id, true));
                        }
                    }
                    case ClientboundSetEntityDataPacket metadata -> {
                        // Keep earlier poses on this entity ahead of an ordered content change.
                        var movement = this.poses.remove(poseKey(metadata.id(), false));
                        var transform = this.poses.remove(poseKey(metadata.id(), true));
                        if (transform != null && movement != null && transform.order < movement.order) {
                            this.ordered.add(transform);
                            this.ordered.add(movement);
                        } else {
                            if (movement != null) this.ordered.add(movement);
                            if (transform != null) this.ordered.add(transform);
                        }
                    }
                    default -> {}
                }
                this.ordered.add(new QueuedPacket(packet));
            }
        }
    }

    List<DisplayPacket> drain(Function<ClientboundSetEntityDataPacket, DisplayPacket> wrapMergedMetadata) {
        List<DisplayPacket> result = new ArrayList<>(this.ordered.size() + this.poses.size());
        for (QueuedPacket packet : this.ordered) result.add(packet.finish(wrapMergedMetadata));
        for (QueuedPacket packet : this.poses.values()) result.add(packet.finish(wrapMergedMetadata));
        this.ordered.clear();
        this.poses.clear();
        return result;
    }

    static long poseKey(int entityId, boolean metadata) {
        return (long) entityId << 1 | (metadata ? 1 : 0);
    }

    private static final class QueuedPacket {
        private DisplayPacket latest;
        private Map<Integer, SynchedEntityData.DataValue<?>> mergedFields;
        private long order;

        private QueuedPacket(DisplayPacket packet) {
            this.latest = packet;
        }

        private void replace(DisplayPacket packet) {
            if (packet.packet() instanceof ClientboundSetEntityDataPacket metadata) {
                if (this.mergedFields == null) {
                    this.mergedFields = new HashMap<>();
                    for (var value : ((ClientboundSetEntityDataPacket) this.latest.packet()).packedItems()) {
                        this.mergedFields.put(value.id(), value);
                    }
                }
                for (var value : metadata.packedItems()) this.mergedFields.put(value.id(), value);
            }
            this.latest = packet;
        }

        private DisplayPacket finish(Function<ClientboundSetEntityDataPacket, DisplayPacket> wrapMergedMetadata) {
            if (this.mergedFields != null && this.latest.packet() instanceof ClientboundSetEntityDataPacket metadata
                && this.mergedFields.size() != metadata.packedItems().size()) {
                var fields = this.mergedFields.values().stream().sorted(Comparator.comparingInt(SynchedEntityData.DataValue::id)).toList();
                return wrapMergedMetadata.apply(new ClientboundSetEntityDataPacket(metadata.id(), fields));
            }
            return this.latest;
        }
    }
}
