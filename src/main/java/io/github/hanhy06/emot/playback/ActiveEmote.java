package io.github.hanhy06.emot.playback;

import java.util.Objects;
import java.util.UUID;

public record ActiveEmote(UUID playerUuid, String namespace, String animationName) {
	public ActiveEmote {
		Objects.requireNonNull(playerUuid, "playerUuid");
		Objects.requireNonNull(namespace, "namespace");
		Objects.requireNonNull(animationName, "animationName");
	}
}
