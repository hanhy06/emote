package io.github.hanhy06.emote.playback;

import java.util.Objects;

public record EmotePlaybackStartResult(String errorMessage) {
	public static final EmotePlaybackStartResult SUCCESS = new EmotePlaybackStartResult(null);

	public static EmotePlaybackStartResult failure(String errorMessage) {
		String normalizedErrorMessage = Objects.requireNonNull(errorMessage, "errorMessage").trim();
		if (normalizedErrorMessage.isEmpty()) {
			throw new IllegalArgumentException("errorMessage must not be blank");
		}

		return new EmotePlaybackStartResult(normalizedErrorMessage);
	}

	public boolean isSuccess() {
		return this.errorMessage == null;
	}
}
