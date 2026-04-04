package io.github.hanhy06.emote.network.service;

public record PlayResult(
	boolean isSuccess,
	String errorMessage
) {
	public static PlayResult success() {
		return new PlayResult(true, "");
	}

	public static PlayResult failure(String errorMessage) {
		return new PlayResult(false, java.util.Objects.requireNonNull(errorMessage, "errorMessage").trim());
	}
}
