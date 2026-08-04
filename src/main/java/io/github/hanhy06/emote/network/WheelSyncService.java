package io.github.hanhy06.emote.network;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.emote.PlayableEmoteService;
import io.github.hanhy06.emote.network.payload.EmoteWheelSyncPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

public class WheelSyncService {
    public static WheelSyncService INSTANCE;

    private final PlayableEmoteService playableEmoteService;

    public WheelSyncService(PlayableEmoteService playableEmoteService) {
        INSTANCE = this;

        this.playableEmoteService = playableEmoteService;
    }

    public void syncPlayer(ServerPlayer player) {
        if (!ServerPlayNetworking.canSend(player, EmoteWheelSyncPayload.TYPE)) {
            return;
        }

        ServerPlayNetworking.send(player, new EmoteWheelSyncPayload(this.playableEmoteService.getPlayableEmotes(player)));
    }

    public void syncAll() {
        for (ServerPlayer player : Emote.SERVER.getPlayerList().getPlayers()) {
            syncPlayer(player);
        }
    }
}
