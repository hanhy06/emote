package io.github.hanhy06.emote.emote;

import java.util.Objects;

public record PlayableEmoteSelection(
    EmoteDefinition definition,
    String errorMessage
) {
    public PlayableEmoteSelection {
        boolean hasDefinition = definition != null;
        boolean hasErrorMessage = errorMessage != null && !errorMessage.isBlank();
        if (hasDefinition == hasErrorMessage) {
            throw new IllegalArgumentException("definition or errorMessage must be set");
        }
        if (hasErrorMessage) {
            errorMessage = errorMessage.trim();
        }
    }

    public static PlayableEmoteSelection success(EmoteDefinition definition) {
        return new PlayableEmoteSelection(Objects.requireNonNull(definition, "definition"), null);
    }

    public static PlayableEmoteSelection failure(String errorMessage) {
        return new PlayableEmoteSelection(null, Objects.requireNonNull(errorMessage, "errorMessage"));
    }

    public boolean isSuccess() {
        return this.definition != null;
    }
}
