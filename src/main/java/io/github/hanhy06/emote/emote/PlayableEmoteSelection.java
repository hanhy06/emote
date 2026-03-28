package io.github.hanhy06.emote.emote;

import java.util.Objects;

public record PlayableEmoteSelection(
	EmoteDefinition definition,
	EmoteAnimation animation
) {
	public PlayableEmoteSelection {
		Objects.requireNonNull(definition, "definition");
		Objects.requireNonNull(animation, "animation");
	}
}
