package io.github.hanhy06.emote.emote;

import java.util.Objects;

public record PlayableEmote(
	String commandName,
	String animationName,
	boolean defaultAnimation,
	String displayName,
	String description
) {
	public PlayableEmote {
		Objects.requireNonNull(commandName, "commandName");
		Objects.requireNonNull(animationName, "animationName");
		Objects.requireNonNull(displayName, "displayName");
		Objects.requireNonNull(description, "description");
	}

	public String createPlayCommand() {
		return this.defaultAnimation
			? "emote play " + this.commandName
			: "emote play " + this.commandName + " " + this.animationName;
	}

	public String selectionKey() {
		return this.commandName + "\u0000" + this.animationName;
	}
}
