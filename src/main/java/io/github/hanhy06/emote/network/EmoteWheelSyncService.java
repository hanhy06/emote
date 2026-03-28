package io.github.hanhy06.emote.network;

import io.github.hanhy06.emote.emote.PlayableEmoteService;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public class EmoteWheelSyncService {
	private final PlayableEmoteService playableEmoteService;

	public EmoteWheelSyncService(PlayableEmoteService playableEmoteService) {
		this.playableEmoteService = playableEmoteService;
	}

	public void syncPlayer(ServerPlayer player) {
		if (!ServerPlayNetworking.canSend(player, EmoteWheelSyncPayload.TYPE)) {
			return;
		}

		ServerPlayNetworking.send(player, new EmoteWheelSyncPayload(this.playableEmoteService.getPlayableEmotes(player)));
	}

	public void syncAll(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			syncPlayer(player);
		}
	}
}
