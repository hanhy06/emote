package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.api.PlaybackStopReason;
import net.minecraft.server.level.ServerPlayer;

public interface PlaybackStateListener {
    void onStarted(ServerPlayer player, PlaybackSession session, PlayerPlaybackState playerState);

    void onStopped(ServerPlayer player, PlaybackSession session, PlayerPlaybackState playerState, PlaybackStopReason reason);
    default void onCancelled(ServerPlayer player, PlaybackSession session, PlayerPlaybackState playerState, PlaybackStopReason reason) {}
}
