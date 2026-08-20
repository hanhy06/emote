package io.github.hanhy06.emote.network;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.application.EmoteQueryService;
import io.github.hanhy06.emote.network.payload.WheelSyncPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

public class WheelSyncService {
    private final EmoteQueryService emoteQueryService;

    public WheelSyncService(EmoteQueryService emoteQueryService) {
        this.emoteQueryService = emoteQueryService;
    }

    public void syncPlayer(ServerPlayer player) {
        if (!ServerPlayNetworking.canSend(player, WheelSyncPayload.TYPE)) {
            return;
        }

        ServerPlayNetworking.send(player, new WheelSyncPayload(this.emoteQueryService.getAll(player)));
    }

    public void syncAll() {
        for (ServerPlayer player : EmoteMod.SERVER.getPlayerList().getPlayers()) {
            syncPlayer(player);
        }
    }
}
