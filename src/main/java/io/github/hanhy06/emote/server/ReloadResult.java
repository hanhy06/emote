package io.github.hanhy06.emote.server;

public record ReloadResult(
    int disabledEmoteCount,
    int permissionRuleCount,
    int detectedFileCount,
    int loadedEmoteCount,
    boolean successful
) {
    public ReloadResult(int disabledEmoteCount, int permissionRuleCount, int detectedFileCount, int loadedEmoteCount) {
        this(disabledEmoteCount, permissionRuleCount, detectedFileCount, loadedEmoteCount, true);
    }
}
