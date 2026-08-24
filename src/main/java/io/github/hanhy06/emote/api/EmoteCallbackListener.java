package io.github.hanhy06.emote.api;

@FunctionalInterface
public interface EmoteCallbackListener {
    void onCallback(EmoteCallbackEvent event);
}
