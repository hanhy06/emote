package io.github.hanhy06.emote.api;

@FunctionalInterface
public interface EmotePlayListener {
    void onStarting(EmotePlayEvent event);
}
