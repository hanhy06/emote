package io.github.hanhy06.emote.animation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

final class EmoteJsonDocument {
    static final int MAX_JSON_BYTES = 8 * 1_024 * 1_024;

    private final Path sourcePath;
    private final byte[] bytes;
    private final JsonObject root;
    private final EmoteJsonReader reader;
    private final String type;

    private EmoteJsonDocument(
        Path sourcePath,
        byte[] bytes,
        JsonObject root,
        EmoteJsonReader reader,
        String type
    ) {
        this.sourcePath = sourcePath;
        this.bytes = bytes;
        this.root = root;
        this.reader = reader;
        this.type = type;
    }

    static EmoteJsonDocument read(Path sourcePath) throws EmoteAnimationLoadException {
        Objects.requireNonNull(sourcePath, "sourcePath");
        try {
            if (Files.size(sourcePath) > MAX_JSON_BYTES) {
                throw tooLarge(sourcePath);
            }
            return parse(sourcePath, Files.readAllBytes(sourcePath));
        } catch (IOException exception) {
            throw new EmoteAnimationLoadException(sourcePath, "$", "failed to read file", exception);
        }
    }

    static EmoteJsonDocument parse(Path sourcePath, byte[] bytes) throws EmoteAnimationLoadException {
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length > MAX_JSON_BYTES) {
            throw tooLarge(sourcePath);
        }

        EmoteJsonReader reader = new EmoteJsonReader(sourcePath);
        JsonElement rootElement;
        try {
            rootElement = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        } catch (JsonParseException exception) {
            throw reader.error("$", "invalid JSON", exception);
        }
        if (!rootElement.isJsonObject()) {
            throw reader.error("$", "must be an object");
        }

        JsonObject root = rootElement.getAsJsonObject();
        String type = reader.requireString(root, "type", "$");
        return new EmoteJsonDocument(sourcePath, bytes, root, reader, type);
    }

    Path sourcePath() {
        return this.sourcePath;
    }

    byte[] bytes() {
        return this.bytes;
    }

    JsonObject root() {
        return this.root;
    }

    EmoteJsonReader reader() {
        return this.reader;
    }

    String type() {
        return this.type;
    }

    private static EmoteAnimationLoadException tooLarge(Path sourcePath) {
        return new EmoteAnimationLoadException(
            sourcePath,
            "$",
            "file must not exceed " + MAX_JSON_BYTES + " bytes"
        );
    }
}
