package io.github.hanhy06.emote.server;

public record ReloadResult(
    boolean configLoaded,
    boolean accessConfigLoaded,
    int emoteCount
) {
}
