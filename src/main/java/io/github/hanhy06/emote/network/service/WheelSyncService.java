package io.github.hanhy06.emote.network.service;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.emote.PlayableEmoteService;
import io.github.hanhy06.emote.network.payload.EmoteWheelSyncPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public class WheelSyncService {
	private final PlayableEmoteService playableEmoteService;

	public WheelSyncService(PlayableEmoteService playableEmoteService) {
		this.playableEmoteService = playableEmoteService;
	}

	public void syncPlayer(ServerPlayer player) {
		if (!ServerPlayNetworking.canSend(player, EmoteWheelSyncPayload.TYPE)) {
			return;
		}

		ServerPlayNetworking.send(player, new EmoteWheelSyncPayload(this.playableEmoteService.getPlayableEmotes(player)));
	}

	public void syncAll() {
		MinecraftServer server = Emote.SERVER;
		if (server == null) {
			return;
		}

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			syncPlayer(player);
		}
	}
}
