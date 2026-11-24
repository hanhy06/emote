package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.network.VirtualDisplaySender;
import io.github.hanhy06.emote.network.VirtualDisplaySender.DisplayPacket;
import io.github.hanhy06.emote.playback.PlaybackScene.AttachmentInstance;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.*;

public final class VirtualDisplayTracker {
    private final Set<PlaybackScene> scenes = new LinkedHashSet<>();
    private final Set<ServerGamePacketListenerImpl> changedWorld = new HashSet<>();
    private Map<ServerGamePacketListenerImpl, IntArrayList> removals = new LinkedHashMap<>();

    void add(PlaybackScene scene) {
        if (scene.displayAttachments().isEmpty() || !this.scenes.add(scene)) return;
        for (AttachmentInstance attachment : scene.displayAttachments()) attachment.packetUuid = attachment.entity().getUUID();
    }

    void remove(PlaybackScene scene) {
        if (!this.scenes.remove(scene)) return;
        for (AttachmentInstance attachment : scene.displayAttachments()) {
            for (ServerGamePacketListenerImpl viewer : attachment.viewers) removeFor(viewer, scene.level(), attachment.entity().getId());
            attachment.viewers = Set.of();
            attachment.lastPosition = null;
        }
    }

    void viewerChangedWorld(ServerGamePacketListenerImpl viewer) {
        VirtualDisplaySender.INSTANCE.viewerChangedWorld(viewer);
        this.changedWorld.add(viewer);
        this.removals.remove(viewer);
    }

    @Nullable Runnable capture() {
        List<DisplayUpdate> updates = new ArrayList<>();
        for (PlaybackScene scene : this.scenes) {
            Map<Vec3, Set<ServerGamePacketListenerImpl>> viewersByPosition = new HashMap<>();
            for (AttachmentInstance attachment : scene.displayAttachments()) {
                Display entity = attachment.entity();
                Set<ServerGamePacketListenerImpl> viewers = entity.isRemoved() ? Set.of() : viewersByPosition.computeIfAbsent(
                    entity.position(), ignored -> viewersAt(scene.level(), entity));
                DisplayUpdate update = capture(scene.level(), attachment, viewers);
                if (update != null) updates.add(update);
            }
        }
        this.changedWorld.clear();
        if (updates.isEmpty() && this.removals.isEmpty() && !VirtualDisplaySender.INSTANCE.hasPendingDeliveries()) return null;
        var removed = this.removals;
        this.removals = new LinkedHashMap<>();
        return () -> {
            Map<ServerGamePacketListenerImpl, List<DisplayPacket>> outbound = new LinkedHashMap<>();
            Map<IntArrayList, DisplayPacket> removalsByIds = new HashMap<>();
            removed.forEach((viewer, ids) -> enqueue(outbound, viewer,
                removalsByIds.computeIfAbsent(ids, entityIds -> VirtualDisplaySender.INSTANCE.packet(new ClientboundRemoveEntitiesPacket(entityIds)))));
            for (DisplayUpdate update : updates) update.enqueue(outbound);
            VirtualDisplaySender.INSTANCE.send(outbound);
        };
    }

    private @Nullable DisplayUpdate capture(ServerLevel level, AttachmentInstance attachment, Set<ServerGamePacketListenerImpl> viewers) {
        Display display = attachment.entity();
        int id = display.getId();
        for (ServerGamePacketListenerImpl viewer : attachment.viewers) {
            if (!viewers.contains(viewer)) removeFor(viewer, level, id);
        }
        var previousViewers = attachment.viewers;
        attachment.viewers = viewers;
        if (viewers.isEmpty()) return null;

        var position = Position.capture(display);
        boolean moved = !position.equals(attachment.lastPosition);
        attachment.lastPosition = position;
        var dirty = display.getEntityData().packDirty();
        List<ServerGamePacketListenerImpl> entering = new ArrayList<>();
        List<ServerGamePacketListenerImpl> staying = new ArrayList<>();
        for (ServerGamePacketListenerImpl viewer : viewers) {
            if (!previousViewers.contains(viewer) || this.changedWorld.contains(viewer)) entering.add(viewer);
            else if (moved || dirty != null) staying.add(viewer);
        }
        if (entering.isEmpty() && staying.isEmpty()) return null;
        var initial = entering.isEmpty() ? null : display.getEntityData().getNonDefaultValues();
        return new DisplayUpdate(id, attachment.packetUuid, display.getType(), position, display.getDeltaMovement(),
            moved, initial, dirty, entering, staying);
    }

    private void removeFor(ServerGamePacketListenerImpl viewer, ServerLevel level, int id) {
        if (viewer.player.level() == level && viewer.isAcceptingMessages() && !this.changedWorld.contains(viewer)) {
            this.removals.computeIfAbsent(viewer, ignored -> new IntArrayList()).add(id);
        }
    }

    private static Set<ServerGamePacketListenerImpl> viewersAt(ServerLevel level, Display display) {
        var chunk = display.chunkPosition();
        var chunkMap = level.getChunkSource().chunkMap;
        int trackingRange = level.getServer().getScaledTrackingDistance(display.getType().clientTrackingRange() * 16);
        int serverViewDistance = level.getServer().getPlayerList().getViewDistance();
        Set<ServerGamePacketListenerImpl> viewers = new LinkedHashSet<>();
        for (ServerPlayer player : level.players()) {
            int range = Math.min(trackingRange, Math.clamp(player.requestedViewDistance(), 2, serverViewDistance) * 16);
            double dx = player.getX() - display.getX();
            double dz = player.getZ() - display.getZ();
            if (dx * dx + dz * dz <= (double) range * range && player.connection.isAcceptingMessages()
                && display.broadcastToPlayer(player) && chunkMap.isChunkTracked(player, chunk.x(), chunk.z())) {
                viewers.add(player.connection);
            }
        }
        return viewers;
    }

    record Position(Vec3 value, float yaw, float pitch, float headYaw, boolean onGround) {
        static Position capture(Display display) {
            return new Position(display.position(), display.getYRot(), display.getXRot(), display.getYHeadRot(), display.onGround());
        }

        ClientboundEntityPositionSyncPacket packet(int id) {
            return new ClientboundEntityPositionSyncPacket(id, PositionPath.of(this.value), this.yaw, this.pitch, this.headYaw, this.onGround);
        }
    }

    private static void enqueue(Map<ServerGamePacketListenerImpl, List<DisplayPacket>> outbound,
                                ServerGamePacketListenerImpl viewer, DisplayPacket packet) {
        outbound.computeIfAbsent(viewer, ignored -> new ArrayList<>()).add(packet);
    }

    private record DisplayUpdate(int id, UUID uuid, EntityType<?> type, Position position, Vec3 velocity,
                                 boolean moved, @Nullable List<SynchedEntityData.DataValue<?>> initial,
                                 @Nullable List<SynchedEntityData.DataValue<?>> dirty,
                                 List<ServerGamePacketListenerImpl> entering, List<ServerGamePacketListenerImpl> staying) {
        private void enqueue(Map<ServerGamePacketListenerImpl, List<DisplayPacket>> outbound) {
            if (!this.entering.isEmpty()) {
                Vec3 location = this.position.value();
                Packet<ClientGamePacketListener> spawn = new ClientboundAddEntityPacket(this.id, this.uuid,
                    location.x(), location.y(), location.z(), this.position.pitch(), this.position.yaw(), this.type, 0, this.velocity, this.position.headYaw());
                if (this.initial != null) spawn = new ClientboundBundlePacket(List.of(spawn, new ClientboundSetEntityDataPacket(this.id, this.initial)));
                DisplayPacket spawnPacket = VirtualDisplaySender.INSTANCE.packet(spawn);
                for (ServerGamePacketListenerImpl viewer : this.entering) VirtualDisplayTracker.enqueue(outbound, viewer, spawnPacket);
            }
            if (this.staying.isEmpty()) return;
            DisplayPacket movement = this.moved ? VirtualDisplaySender.INSTANCE.packet(this.position.packet(this.id)) : null;
            DisplayPacket metadata = this.dirty == null ? null : VirtualDisplaySender.INSTANCE.packet(new ClientboundSetEntityDataPacket(this.id, this.dirty));
            for (ServerGamePacketListenerImpl viewer : this.staying) {
                if (movement != null) VirtualDisplayTracker.enqueue(outbound, viewer, movement);
                if (metadata != null) VirtualDisplayTracker.enqueue(outbound, viewer, metadata);
            }
        }
    }
}
