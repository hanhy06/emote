package io.github.hanhy06.emot.emote;

import java.util.Objects;

public record EmoteAnimation(String name, int keyframeCount) {
	public EmoteAnimation {
		Objects.requireNonNull(name, "name");

		if (keyframeCount < 0) {
			throw new IllegalArgumentException("keyframeCount must be zero or greater");
		}
	}
}
