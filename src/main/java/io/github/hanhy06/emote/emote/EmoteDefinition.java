package io.github.hanhy06.emote.emote;

import io.github.hanhy06.emote.skin.EmoteSkinPart;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record EmoteDefinition(
        String namespace,
        String name,
        String description,
        String commandName,
        String entrypoint,
        boolean hidePlayer,
        Path datapackPath,
        int partCount,
        List<EmoteSkinPart> skinParts
) {
    public EmoteDefinition {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(commandName, "commandName");
        Objects.requireNonNull(entrypoint, "entrypoint");
        Objects.requireNonNull(datapackPath, "datapackPath");
        Objects.requireNonNull(skinParts, "skinParts");
        if (name.isBlank() || description.isBlank() || commandName.isBlank() || entrypoint.isBlank()) {
            throw new IllegalArgumentException("Emote definition text fields must not be blank");
        }
        if (partCount < 0) {
            throw new IllegalArgumentException("partCount must be zero or greater");
        }
        skinParts = List.copyOf(skinParts);
    }
}
