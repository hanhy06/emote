package io.github.hanhy06.emote.server;

public record EmoteReloadResult(
    boolean configLoaded,
    boolean emoteAccessConfigLoaded,
    int emoteCount
) {
}
