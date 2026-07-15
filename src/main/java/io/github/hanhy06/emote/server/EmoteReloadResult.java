package io.github.hanhy06.emote.server;

public record EmoteReloadResult(
    boolean configLoaded,
    boolean packConfigLoaded,
    int emoteCount
) {
}
