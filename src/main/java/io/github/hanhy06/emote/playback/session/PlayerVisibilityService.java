package io.github.hanhy06.emote.playback.session;

import com.mojang.datafixers.util.Pair;
import io.github.hanhy06.emote.mixin.accessor.EntitySharedFlagsAccessor;
import io.github.hanhy06.emote.playback.PlaybackEngine;
import io.github.hanhy06.emote.playback.PlaybackHooks;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;

public final class PlayerVisibilityService {
    private static final List<EquipmentSlot> PLAYER_EQUIPMENT_SLOTS = List.of(
        EquipmentSlot.MAINHAND,
        EquipmentSlot.OFFHAND,
        EquipmentSlot.FEET,
        EquipmentSlot.LEGS,
        EquipmentSlot.CHEST,
        EquipmentSlot.HEAD
    );
    private static final List<Pair<EquipmentSlot, ItemStack>> EMPTY_EQUIPMENT = PLAYER_EQUIPMENT_SLOTS.stream()
        .map(slot -> Pair.of(slot, ItemStack.EMPTY))
        .toList();

    private final PlaybackEngine playbackEngine;

    public PlayerVisibilityService(PlaybackEngine playbackEngine) {
        this.playbackEngine = playbackEngine;
    }

    public void register() {
        EntityTrackingEvents.START_TRACKING.register(this::handleStartTracking);
        PlaybackHooks.EQUIPMENT_SYNC.register(this::handleEquipmentSync);
    }

    public void start(ServerPlayer player, PlaybackSession session, PlaybackParticipant participant) {
        if (!session.playerBehavior().hidden()) {
            return;
        }
        player.setInvisible(true);
        syncPlayerVisibility(player);
        sendToTrackingPlayers(player, EMPTY_EQUIPMENT);
    }

    public void tick(ServerPlayer player, PlaybackSession session, PlaybackParticipant participant) {
        if (!session.playerBehavior().hidden() || player.isInvisible()) {
            return;
        }
        player.setInvisible(true);
        syncPlayerVisibility(player);
    }

    public void stop(ServerPlayer player, PlaybackSession session, PlaybackParticipant participant) {
        if (!session.playerBehavior().hidden()) {
            return;
        }
        player.setInvisible(participant.wasInvisible());
        syncPlayerVisibility(player);
        sendToTrackingPlayers(player, createVisibleEquipment(player));
    }

    private void handleStartTracking(Entity entity, ServerPlayer trackingPlayer) {
        if (!(entity instanceof ServerPlayer emotePlayer)) {
            return;
        }
        PlaybackSession session = this.playbackEngine.findActive(emotePlayer.getUUID());
        if (session != null && session.playerBehavior().hidden()) {
            trackingPlayer.connection.send(new ClientboundSetEquipmentPacket(emotePlayer.getId(), EMPTY_EQUIPMENT));
        }
    }

    private void handleEquipmentSync(ServerPlayer player, Map<EquipmentSlot, ItemStack> changedItems) {
        if (PLAYER_EQUIPMENT_SLOTS.stream().noneMatch(changedItems::containsKey)) {
            return;
        }
        PlaybackSession session = this.playbackEngine.findActive(player.getUUID());
        if (session != null && session.playerBehavior().hidden()) {
            sendToTrackingPlayers(player, EMPTY_EQUIPMENT);
        }
    }

    private static void syncPlayerVisibility(ServerPlayer player) {
        EntityDataAccessor<Byte> sharedFlagsId = EntitySharedFlagsAccessor.emote$getSharedFlagsId();
        byte sharedFlags = player.getEntityData().get(sharedFlagsId);
        ClientboundSetEntityDataPacket packet = new ClientboundSetEntityDataPacket(
            player.getId(),
            List.of(SynchedEntityData.DataValue.create(sharedFlagsId, sharedFlags))
        );
        player.level().getChunkSource().sendToTrackingPlayersAndSelf(player, packet);
    }

    private static List<Pair<EquipmentSlot, ItemStack>> createVisibleEquipment(ServerPlayer player) {
        return PLAYER_EQUIPMENT_SLOTS.stream()
            .map(slot -> Pair.of(slot, player.getItemBySlot(slot).copy()))
            .toList();
    }

    private static void sendToTrackingPlayers(ServerPlayer player, List<Pair<EquipmentSlot, ItemStack>> equipment) {
        player.level().getChunkSource().sendToTrackingPlayers(
            player,
            new ClientboundSetEquipmentPacket(player.getId(), equipment)
        );
    }
}
