package io.github.hanhy06.emote.skin;

import java.util.Objects;

public record EmoteSkinPart(
	int partIndex,
	PlayerSkinPart skinPart
) {
	public EmoteSkinPart {
		if (partIndex < 0) {
			throw new IllegalArgumentException("partIndex must be zero or greater");
		}

		Objects.requireNonNull(skinPart, "skinPart");
	}
}
