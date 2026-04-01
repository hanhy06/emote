package io.github.hanhy06.emote.network.service;

import io.github.hanhy06.emote.network.payload.EmotePlaybackStatePayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

public class PlaybackStateService {
	private static final EmotePlaybackStatePayload ACTIVE_PAYLOAD = new EmotePlaybackStatePayload(true);
	private static final EmotePlaybackStatePayload INACTIVE_PAYLOAD = new EmotePlaybackStatePayload(false);

	public void syncActive(ServerPlayer player) {
		sync(player, ACTIVE_PAYLOAD);
	}

	public void syncInactive(ServerPlayer player) {
		sync(player, INACTIVE_PAYLOAD);
	}

	private void sync(ServerPlayer player, EmotePlaybackStatePayload payload) {
		if (!ServerPlayNetworking.canSend(player, EmotePlaybackStatePayload.TYPE)) {
			return;
		}

		ServerPlayNetworking.send(player, payload);
	}
}
