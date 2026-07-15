package io.github.hanhy06.emote.playback;

import com.mojang.datafixers.util.Pair;
import io.github.hanhy06.emote.mixin.EntitySharedFlagsAccessor;
import io.github.hanhy06.emote.mixin.bridge.PlaybackHooks;
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

final class PlayerVisibilityService {
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

    private final PlaybackManager playbackManager;

    PlayerVisibilityService(PlaybackManager playbackManager) {
        this.playbackManager = playbackManager;
    }

    void register() {
        EntityTrackingEvents.START_TRACKING.register(this::handleStartTracking);
        PlaybackHooks.EQUIPMENT_SYNC.register(this::handleEquipmentSync);
    }

    void start(ServerPlayer player, ActiveEmote activeEmote) {
        if (activeEmote.playerVisibilityManaged()) {
            player.setInvisible(true);
            syncPlayerVisibility(player);
        }
        sendToTrackingPlayers(player, EMPTY_EQUIPMENT);
    }

    void tick(ServerPlayer player, ActiveEmote activeEmote) {
        if (!activeEmote.playerVisibilityManaged()) {
            return;
        }
        player.setInvisible(true);
        syncPlayerVisibility(player);
    }

    void stop(ServerPlayer player, ActiveEmote activeEmote) {
        if (activeEmote.playerVisibilityManaged()) {
            player.setInvisible(activeEmote.wasInvisible());
        }
        sendToTrackingPlayers(player, createVisibleEquipment(player));
    }

    private void handleStartTracking(Entity entity, ServerPlayer trackingPlayer) {
        if (entity instanceof ServerPlayer emotePlayer
            && this.playbackManager.findActiveEmote(emotePlayer.getUUID()) != null) {
            trackingPlayer.connection.send(createEquipmentPacket(emotePlayer, EMPTY_EQUIPMENT));
        }
    }

    private void handleEquipmentSync(ServerPlayer player, Map<EquipmentSlot, ItemStack> changedItems) {
        if (PLAYER_EQUIPMENT_SLOTS.stream().noneMatch(changedItems::containsKey)) {
            return;
        }
        if (this.playbackManager.findActiveEmote(player.getUUID()) != null) {
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
        player.level().getChunkSource().sendToTrackingPlayers(player, createEquipmentPacket(player, equipment));
    }

    private static ClientboundSetEquipmentPacket createEquipmentPacket(
        ServerPlayer player,
        List<Pair<EquipmentSlot, ItemStack>> equipment
    ) {
        return new ClientboundSetEquipmentPacket(player.getId(), equipment);
    }
}
