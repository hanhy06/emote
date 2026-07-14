package io.github.hanhy06.emote.playback;

import com.mojang.datafixers.util.Pair;
import io.github.hanhy06.emote.playback.data.ActiveEmote;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;

public class HeldItemVisibilityService implements PlaybackStateListener {
    private static final List<Pair<EquipmentSlot, ItemStack>> EMPTY_HANDS = List.of(
        Pair.of(EquipmentSlot.MAINHAND, ItemStack.EMPTY),
        Pair.of(EquipmentSlot.OFFHAND, ItemStack.EMPTY)
    );

    private final PlaybackManager playbackManager;

    public HeldItemVisibilityService(PlaybackManager playbackManager) {
        this.playbackManager = playbackManager;
    }

    public void register() {
        EntityTrackingEvents.START_TRACKING.register(this::handleStartTracking);
        PlaybackEquipmentSyncCallback.EVENT.register(this::handleEquipmentSync);
    }

    @Override
    public void onEmoteStarted(ServerPlayer player, ActiveEmote activeEmote) {
        sendToTrackingPlayers(player, EMPTY_HANDS);
    }

    @Override
    public void onEmoteStopped(ServerPlayer player, ActiveEmote activeEmote) {
        sendToTrackingPlayers(player, List.of(
            Pair.of(EquipmentSlot.MAINHAND, player.getMainHandItem().copy()),
            Pair.of(EquipmentSlot.OFFHAND, player.getOffhandItem().copy())
        ));
    }

    private void handleStartTracking(Entity entity, ServerPlayer trackingPlayer) {
        if (entity instanceof ServerPlayer emotePlayer && this.playbackManager.findActiveEmote(emotePlayer.getUUID()) != null) {
            trackingPlayer.connection.send(createPacket(emotePlayer, EMPTY_HANDS));
        }
    }

    private void handleEquipmentSync(ServerPlayer player, Map<EquipmentSlot, ItemStack> changedItems) {
        if (!changedItems.containsKey(EquipmentSlot.MAINHAND) && !changedItems.containsKey(EquipmentSlot.OFFHAND)) {
            return;
        }
        if (this.playbackManager.findActiveEmote(player.getUUID()) != null) {
            sendToTrackingPlayers(player, EMPTY_HANDS);
        }
    }

    private static void sendToTrackingPlayers(ServerPlayer player, List<Pair<EquipmentSlot, ItemStack>> equipment) {
        ((ServerLevel) player.level()).getChunkSource().sendToTrackingPlayers(player, createPacket(player, equipment));
    }

    private static ClientboundSetEquipmentPacket createPacket(
        ServerPlayer player,
        List<Pair<EquipmentSlot, ItemStack>> equipment
    ) {
        return new ClientboundSetEquipmentPacket(player.getId(), equipment);
    }
}
