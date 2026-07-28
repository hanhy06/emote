package io.github.hanhy06.emote.api;

@FunctionalInterface
public interface EmotePlayListener {
    void beforePlay(EmotePlayEvent event);
}
