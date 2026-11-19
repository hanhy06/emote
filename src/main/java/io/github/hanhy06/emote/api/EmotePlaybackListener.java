package io.github.hanhy06.emote.api;

public interface EmotePlaybackListener {
    default void onStarted(Playback playback) {
    }

    default void onStopped(Playback playback, PlaybackStopReason reason) {
    }
}
