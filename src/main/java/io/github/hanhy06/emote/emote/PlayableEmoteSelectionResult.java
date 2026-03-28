package io.github.hanhy06.emote.emote;

import java.util.Objects;

public record PlayableEmoteSelectionResult(
	PlayableEmoteSelection selection,
	String errorMessage
) {
	public PlayableEmoteSelectionResult {
		boolean hasSelection = selection != null;
		boolean hasErrorMessage = errorMessage != null && !errorMessage.isBlank();
		if (hasSelection == hasErrorMessage) {
			throw new IllegalArgumentException("selection or errorMessage must be set");
		}
	}

	public static PlayableEmoteSelectionResult success(PlayableEmoteSelection selection) {
		return new PlayableEmoteSelectionResult(Objects.requireNonNull(selection, "selection"), null);
	}

	public static PlayableEmoteSelectionResult failure(String errorMessage) {
		String normalizedErrorMessage = Objects.requireNonNull(errorMessage, "errorMessage").trim();
		if (normalizedErrorMessage.isEmpty()) {
			throw new IllegalArgumentException("errorMessage must not be blank");
		}

		return new PlayableEmoteSelectionResult(null, normalizedErrorMessage);
	}

	public boolean isSuccess() {
		return this.selection != null;
	}
}
