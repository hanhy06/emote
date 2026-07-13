package io.github.hanhy06.emote.bdengine;

import java.util.Objects;

record EmoteDatapackMetadata(
        int schema_version,
        String name,
        String description,
        String command_name,
        String default_animation,
        boolean hide_player
) {
    static final int CURRENT_SCHEMA_VERSION = 1;

    EmoteDatapackMetadata {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(command_name, "command_name");
        Objects.requireNonNull(default_animation, "default_animation");

        if (schema_version != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported emote metadata schema_version: " + schema_version);
        }
        if (name.isBlank() || description.isBlank() || command_name.isBlank() || default_animation.isBlank()) {
            throw new IllegalArgumentException("Emote metadata text fields must not be blank");
        }
    }
}
