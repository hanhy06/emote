package io.github.hanhy06.emote.server;

public record ReloadResult(
    int disabledEmoteCount,
    int permissionRuleCount,
    int detectedFileCount,
    int loadedEmoteCount,
    Failure failure
) {
    public ReloadResult(int disabledEmoteCount, int permissionRuleCount, int detectedFileCount, int loadedEmoteCount) {
        this(disabledEmoteCount, permissionRuleCount, detectedFileCount, loadedEmoteCount, Failure.NONE);
    }

    public boolean successful() {
        return this.failure == Failure.NONE;
    }

    public enum Failure {
        NONE,
        EMOTE_LOAD,
        RESOURCE_PACK_BUILD
    }
}
