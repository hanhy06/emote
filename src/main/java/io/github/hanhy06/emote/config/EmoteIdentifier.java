package io.github.hanhy06.emote.config;

import java.util.Objects;

public record EmoteIdentifier(
	String datapack_identifier,
	String name,
	String command_name,
	String description,
	String default_animation_name
) {
	public EmoteIdentifier {
		Objects.requireNonNull(datapack_identifier, "datapack_identifier");
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(command_name, "command_name");
		Objects.requireNonNull(description, "description");
		Objects.requireNonNull(default_animation_name, "default_animation_name");
	}
}
