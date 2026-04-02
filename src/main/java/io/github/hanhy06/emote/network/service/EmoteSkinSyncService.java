package io.github.hanhy06.emote.network.service;

import io.github.hanhy06.emote.network.payload.EmoteSkinSyncPayload;
import io.github.hanhy06.emote.playback.PlaybackManager;
import io.github.hanhy06.emote.playback.data.ActiveEmote;
import io.github.hanhy06.emote.skin.PreparedPlayerSkin;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

public class EmoteSkinSyncService {
	private final PlaybackManager playbackManager;

	public EmoteSkinSyncService(PlaybackManager playbackManager) {
		this.playbackManager = playbackManager;
	}

	public void syncPlayer(ServerPlayer player) {
		if (!ServerPlayNetworking.canSend(player, EmoteSkinSyncPayload.TYPE)) {
			return;
		}

		ServerPlayNetworking.send(player, createPayload());
	}

	public void syncAll(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			syncPlayer(player);
		}
	}

	private EmoteSkinSyncPayload createPayload() {
		List<EmoteSkinSyncPayload.Entry> entries = new ArrayList<>();
		for (ActiveEmote activeEmote : this.playbackManager.getActiveEmotes()) {
			PreparedPlayerSkin preparedPlayerSkin = activeEmote.preparedPlayerSkin();
			if (preparedPlayerSkin == null) {
				continue;
			}

			entries.add(new EmoteSkinSyncPayload.Entry(
					activeEmote.namespace(),
					preparedPlayerSkin.textureHash(),
					preparedPlayerSkin.slimModel(),
					activeEmote.skinParts()
			));
		}

		return new EmoteSkinSyncPayload(entries);
	}
}
