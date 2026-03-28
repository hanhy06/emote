package io.github.hanhy06.emote.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

public class EmotePlaybackStateService {
	private static final EmotePlaybackStatePayload ACTIVE_PAYLOAD = new EmotePlaybackStatePayload(true);
	private static final EmotePlaybackStatePayload INACTIVE_PAYLOAD = new EmotePlaybackStatePayload(false);

	public void syncActive(ServerPlayer player) {
		if (ServerPlayNetworking.canSend(player, EmotePlaybackStatePayload.TYPE)) {
			ServerPlayNetworking.send(player, ACTIVE_PAYLOAD);
		}
	}

	public void syncInactive(ServerPlayer player) {
		if (ServerPlayNetworking.canSend(player, EmotePlaybackStatePayload.TYPE)) {
			ServerPlayNetworking.send(player, INACTIVE_PAYLOAD);
		}
	}
}
