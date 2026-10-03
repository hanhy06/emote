package io.github.hanhy06.emote.api;

public interface EmoteCallbacks {
    default void onStart(PlaybackContext context) {}

    default void onTick(PlaybackContext context) {}

    default void onLoop(PlaybackContext context) {}

    default void onClose(PlaybackContext context) {}
}
