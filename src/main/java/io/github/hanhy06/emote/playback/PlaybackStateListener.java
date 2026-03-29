package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.playback.data.ActiveEmote;
import net.minecraft.server.level.ServerPlayer;

public interface PlaybackStateListener {
	void onEmoteStarted(ServerPlayer player, ActiveEmote activeEmote);

	void onEmoteStopped(ServerPlayer player, ActiveEmote activeEmote);
}
