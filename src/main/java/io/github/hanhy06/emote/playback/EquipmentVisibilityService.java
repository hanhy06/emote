package io.github.hanhy06.emote.playback;

import com.mojang.datafixers.util.Pair;
import io.github.hanhy06.emote.playback.data.ActiveEmote;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;

public class EquipmentVisibilityService implements PlaybackStateListener {
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

    public EquipmentVisibilityService(PlaybackManager playbackManager) {
        this.playbackManager = playbackManager;
    }

    public void register() {
        EntityTrackingEvents.START_TRACKING.register(this::handleStartTracking);
        PlaybackEquipmentSyncCallback.EVENT.register(this::handleEquipmentSync);
    }

    @Override
    public void onEmoteStarted(ServerPlayer player, ActiveEmote activeEmote) {
        sendToTrackingPlayers(player, EMPTY_EQUIPMENT);
    }

    @Override
    public void onEmoteStopped(ServerPlayer player, ActiveEmote activeEmote) {
        sendToTrackingPlayers(player, createVisibleEquipment(player));
    }

    private void handleStartTracking(Entity entity, ServerPlayer trackingPlayer) {
        if (entity instanceof ServerPlayer emotePlayer && this.playbackManager.findActiveEmote(emotePlayer.getUUID()) != null) {
            trackingPlayer.connection.send(createPacket(emotePlayer, EMPTY_EQUIPMENT));
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

    private static List<Pair<EquipmentSlot, ItemStack>> createVisibleEquipment(ServerPlayer player) {
        return PLAYER_EQUIPMENT_SLOTS.stream()
            .map(slot -> Pair.of(slot, player.getItemBySlot(slot).copy()))
            .toList();
    }

    private static void sendToTrackingPlayers(ServerPlayer player, List<Pair<EquipmentSlot, ItemStack>> equipment) {
        player.level().getChunkSource().sendToTrackingPlayers(player, createPacket(player, equipment));
    }

    private static ClientboundSetEquipmentPacket createPacket(
        ServerPlayer player,
        List<Pair<EquipmentSlot, ItemStack>> equipment
    ) {
        return new ClientboundSetEquipmentPacket(player.getId(), equipment);
    }
}
