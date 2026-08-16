package io.github.hanhy06.emote.server;

public record ReloadResult(
    int disabledEmoteCount,
    int permissionRuleCount,
    int detectedFileCount,
    int loadedEmoteCount
) {
}
