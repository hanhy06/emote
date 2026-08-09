package io.github.hanhy06.emote.network;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.emote.PlayableEmoteService;
import io.github.hanhy06.emote.network.payload.WheelSyncPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

public class WheelSyncService {
    private final PlayableEmoteService playableEmoteService;

    public WheelSyncService(PlayableEmoteService playableEmoteService) {
        this.playableEmoteService = playableEmoteService;
    }

    public void syncPlayer(ServerPlayer player) {
        if (!ServerPlayNetworking.canSend(player, WheelSyncPayload.TYPE)) {
            return;
        }

        ServerPlayNetworking.send(player, new WheelSyncPayload(this.playableEmoteService.getAll(player)));
    }

    public void syncAll() {
        for (ServerPlayer player : Emote.SERVER.getPlayerList().getPlayers()) {
            syncPlayer(player);
        }
    }
}
