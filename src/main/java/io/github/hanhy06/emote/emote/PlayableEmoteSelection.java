package io.github.hanhy06.emote.emote;

import java.util.Objects;

public record PlayableEmoteSelection(
    RegisteredEmote emote,
    String errorMessage
) {
    public PlayableEmoteSelection {
        boolean hasEmote = emote != null;
        boolean hasErrorMessage = errorMessage != null && !errorMessage.isBlank();
        if (hasEmote == hasErrorMessage) {
            throw new IllegalArgumentException("emote or errorMessage must be set");
        }
        if (hasErrorMessage) {
            errorMessage = errorMessage.trim();
        }
    }

    public static PlayableEmoteSelection success(RegisteredEmote emote) {
        return new PlayableEmoteSelection(Objects.requireNonNull(emote, "emote"), null);
    }

    public static PlayableEmoteSelection failure(String errorMessage) {
        return new PlayableEmoteSelection(null, Objects.requireNonNull(errorMessage, "errorMessage"));
    }

    public boolean isSuccess() {
        return this.emote != null;
    }
}
