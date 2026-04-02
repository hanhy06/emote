package io.github.hanhy06.emote.playback.data;

import io.github.hanhy06.emote.skin.EmoteSkinPart;

import java.util.Objects;

public record BoundEmoteSkinPart(
		int entityId,
		EmoteSkinPart skinPart
) {
	public BoundEmoteSkinPart {
		Objects.requireNonNull(skinPart, "skinPart");
	}
}
