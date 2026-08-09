package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.api.PlaybackStopReason;
import net.minecraft.server.level.ServerPlayer;

public interface PlaybackStateListener {
    void onStarted(ServerPlayer player, ActivePlayback activeEmote);

    void onStopped(ServerPlayer player, ActivePlayback activeEmote, PlaybackStopReason reason);
}
