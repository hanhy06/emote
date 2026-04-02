package io.github.hanhy06.emote.network.service;

import io.github.hanhy06.emote.Emote;
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
			Emote.LOGGER.info("[skin-debug/server] skip skin_sync player={} reason=cannot_send", player.getGameProfile().name());
			return;
		}

		EmoteSkinSyncPayload payload = createPayload();
		Emote.LOGGER.info(
				"[skin-debug/server] send skin_sync player={} entries={} {}",
				player.getGameProfile().name(),
				payload.entries().size(),
				describeEntries(payload.entries())
		);
		ServerPlayNetworking.send(player, payload);
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
				Emote.LOGGER.info(
						"[skin-debug/server] omit skin_sync entry namespace={} reason=prepared_skin_null",
						activeEmote.namespace()
				);
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

	private String describeEntries(List<EmoteSkinSyncPayload.Entry> entries) {
		if (entries.isEmpty()) {
			return "details=empty";
		}

		StringBuilder builder = new StringBuilder("details=");
		for (int index = 0; index < entries.size(); index++) {
			EmoteSkinSyncPayload.Entry entry = entries.get(index);
			if (index > 0) {
				builder.append(", ");
			}
			builder.append(entry.namespace())
					.append('(')
					.append(entry.skinParts().size())
					.append(',')
					.append(entry.slimModel() ? "slim" : "wide")
					.append(')');
		}
		return builder.toString();
	}
}
