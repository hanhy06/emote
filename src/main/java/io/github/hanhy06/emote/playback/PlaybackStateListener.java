package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.playback.data.ActiveEmote;
import net.minecraft.server.level.ServerPlayer;

public interface PlaybackStateListener {
	PlaybackStateListener NONE = new PlaybackStateListener() {
		@Override
		public void onEmoteStarted(ServerPlayer player, ActiveEmote activeEmote) {
		}

		@Override
		public void onEmoteStopped(ServerPlayer player, ActiveEmote activeEmote) {
		}
	};

	void onEmoteStarted(ServerPlayer player, ActiveEmote activeEmote);

	void onEmoteStopped(ServerPlayer player, ActiveEmote activeEmote);
}
