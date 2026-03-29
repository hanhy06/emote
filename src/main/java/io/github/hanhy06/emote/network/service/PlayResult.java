package io.github.hanhy06.emote.network.service;

import java.util.Objects;

public record PlayResult(
	boolean isSuccess,
	String displayName,
	String errorMessage
) {
	public static PlayResult success(String displayName) {
		return new PlayResult(true, Objects.requireNonNull(displayName, "displayName"), "");
	}

	public static PlayResult failure(String errorMessage) {
		return new PlayResult(false, "", Objects.requireNonNull(errorMessage, "errorMessage").trim());
	}
}
