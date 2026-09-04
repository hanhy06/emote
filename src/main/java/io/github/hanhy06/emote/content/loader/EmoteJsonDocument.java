package io.github.hanhy06.emote.content.loader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;
import io.github.hanhy06.emote.util.MinecraftTime;

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

    private EmoteJsonDocument(Path sourcePath, byte[] bytes, JsonObject root) {
        this.sourcePath = sourcePath;
        this.bytes = bytes;
        this.root = root;
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

        JsonElement rootElement;
        try {
            rootElement = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        } catch (JsonParseException exception) {
            throw new EmoteAnimationLoadException(sourcePath, "$", "invalid JSON", exception);
        }
        if (!rootElement.isJsonObject()) {
            throw new EmoteAnimationLoadException(sourcePath, "$", "must be an object");
        }

        JsonObject root = rootElement.getAsJsonObject();
        EmoteJsonDocument document = new EmoteJsonDocument(sourcePath, bytes, root);
        document.requireString(root, "type", "$");
        return document;
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

    String type() {
        return this.root.get("type").getAsString();
    }

    JsonObject requireObject(JsonObject object, String key, String path) throws EmoteAnimationLoadException {
        return requireObject(requireElement(object, key, path), path + "." + key);
    }

    JsonObject requireObject(JsonElement element, String path) throws EmoteAnimationLoadException {
        if (!element.isJsonObject()) {
            throw error(path, "must be an object");
        }
        return element.getAsJsonObject();
    }

    JsonObject optionalObject(JsonObject object, String key, String path) throws EmoteAnimationLoadException {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return requireObject(element, path + "." + key);
    }

    JsonArray requireArray(JsonObject object, String key, String path) throws EmoteAnimationLoadException {
        JsonElement element = requireElement(object, key, path);
        if (!element.isJsonArray()) {
            throw error(path + "." + key, "must be an array");
        }
        return element.getAsJsonArray();
    }

    JsonArray optionalArray(JsonObject object, String key, String path) throws EmoteAnimationLoadException {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonArray()) {
            throw error(path + "." + key, "must be an array");
        }
        return element.getAsJsonArray();
    }

    JsonElement requireElement(JsonObject object, String key, String path) throws EmoteAnimationLoadException {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            throw error(path + "." + key, "is required");
        }
        return element;
    }

    String requireString(JsonObject object, String key, String path) throws EmoteAnimationLoadException {
        JsonElement element = requireElement(object, key, path);
        if (isNotString(element)) {
            throw error(path + "." + key, "must be a string");
        }
        return element.getAsString();
    }

    boolean requireBoolean(JsonObject object, String key, String path) throws EmoteAnimationLoadException {
        JsonElement element = requireElement(object, key, path);
        if (isNotBoolean(element)) {
            throw error(path + "." + key, "must be a boolean");
        }
        return element.getAsBoolean();
    }

    int requireInt(JsonObject object, String key, String path) throws EmoteAnimationLoadException {
        JsonElement element = requireElement(object, key, path);
        if (isNotNumber(element)) {
            throw error(path + "." + key, "must be an integer");
        }
        try {
            return element.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException exception) {
            throw error(path + "." + key, "must be a 32-bit integer");
        }
    }

    int requireTime(JsonObject object, String key, String path, int minimumTicks)
        throws EmoteAnimationLoadException {
        String fieldPath = path + "." + key;
        String value = requireString(object, key, path);
        try {
            return MinecraftTime.parse(value, minimumTicks);
        } catch (IllegalArgumentException exception) {
            throw error(fieldPath, "must be a valid Minecraft time", exception);
        }
    }

    void requireExactInt(JsonObject object, String key, String path, int expected)
        throws EmoteAnimationLoadException {
        int value = requireInt(object, key, path);
        if (value != expected) {
            throw error(path + "." + key, "must equal " + expected);
        }
    }

    double requireFiniteDouble(JsonElement element, String path) throws EmoteAnimationLoadException {
        if (isNotNumber(element)) {
            throw error(path, "must be a number");
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value)) {
            throw error(path, "must be finite");
        }
        return value;
    }

    boolean isNotString(JsonElement element) {
        return !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString();
    }

    EmoteAnimationLoadException error(String fieldPath, String message) {
        return new EmoteAnimationLoadException(this.sourcePath, fieldPath, message);
    }

    EmoteAnimationLoadException error(String fieldPath, String message, Throwable cause) {
        return new EmoteAnimationLoadException(this.sourcePath, fieldPath, message, cause);
    }

    private boolean isNotBoolean(JsonElement element) {
        return !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean();
    }

    private boolean isNotNumber(JsonElement element) {
        return !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber();
    }

    private static EmoteAnimationLoadException tooLarge(Path sourcePath) {
        return new EmoteAnimationLoadException(
            sourcePath,
            "$",
            "file must not exceed " + MAX_JSON_BYTES + " bytes"
        );
    }
}
