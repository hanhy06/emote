package io.github.hanhy06.emote.playback;

import net.minecraft.server.level.ServerPlayer;

public interface EmotePlaybackStateListener {
	void onEmoteStarted(ServerPlayer player, ActiveEmote activeEmote);

	void onEmoteStopped(ServerPlayer player, ActiveEmote activeEmote);
}
