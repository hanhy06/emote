package io.github.hanhy06.emote.playback.data;

import java.util.Objects;

public record PlaybackStartResult(String errorMessage) {
	public static final PlaybackStartResult SUCCESS = new PlaybackStartResult(null);

	public static PlaybackStartResult failure(String errorMessage) {
		String normalizedErrorMessage = Objects.requireNonNull(errorMessage, "errorMessage").trim();
		if (normalizedErrorMessage.isEmpty()) {
			throw new IllegalArgumentException("errorMessage must not be blank");
		}

		return new PlaybackStartResult(normalizedErrorMessage);
	}

	public boolean isSuccess() {
		return this.errorMessage == null;
	}
}
