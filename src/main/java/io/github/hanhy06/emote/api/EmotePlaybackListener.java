package io.github.hanhy06.emote.api;

public interface EmotePlaybackListener {
    default void onStarted(PlaybackInfo playback) {
    }

    default void onStopped(PlaybackInfo playback, PlaybackStopReason reason) {
    }
}
