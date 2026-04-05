package io.github.hanhy06.emote.config.data;

import io.github.hanhy06.emote.emote.EmoteOptions;

import java.util.Objects;

public record IdentifierEntry(
	String datapack_identifier,
	String name,
	String command_name,
	String description,
	String default_animation_name,
	String options
) {
	public IdentifierEntry(
		String datapack_identifier,
		String name,
		String command_name,
		String description,
		String default_animation_name
	) {
		this(datapack_identifier, name, command_name, description, default_animation_name, "");
	}

	public IdentifierEntry {
		Objects.requireNonNull(datapack_identifier, "datapack_identifier");
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(command_name, "command_name");
		Objects.requireNonNull(description, "description");
		Objects.requireNonNull(default_animation_name, "default_animation_name");
		Objects.requireNonNull(options, "options");

		options = EmoteOptions.normalize(options);
	}
}
