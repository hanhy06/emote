package io.github.hanhy06.emote.content.loader;

import com.google.gson.*;
import io.github.hanhy06.emote.api.EmoteCallback;
import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.animation.EmoteAnimation.Node;
import io.github.hanhy06.emote.util.MinecraftTime;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import io.github.hanhy06.emote.api.EmoteLoadException;

final class EmoteJsonDocument {
    static final int MAX_JSON_BYTES = 8 * 1_024 * 1_024;

    private final Path sourcePath;
    private final JsonObject root;

    private EmoteJsonDocument(Path sourcePath, JsonObject root) {
        this.sourcePath = sourcePath;
        this.root = root;
    }

    static EmoteJsonDocument read(Path sourcePath) throws EmoteLoadException {
        Objects.requireNonNull(sourcePath, "sourcePath");
        try {
            if (Files.size(sourcePath) > MAX_JSON_BYTES) {
                throw tooLarge(sourcePath);
            }
            return parse(sourcePath, Files.readAllBytes(sourcePath));
        } catch (IOException exception) {
            throw new EmoteLoadException(sourcePath, "$", "failed to read file", exception);
        }
    }

    static EmoteJsonDocument parse(Path sourcePath, byte[] bytes) throws EmoteLoadException {
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length > MAX_JSON_BYTES) {
            throw tooLarge(sourcePath);
        }

        JsonElement rootElement;
        try {
            rootElement = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        } catch (JsonParseException exception) {
            throw new EmoteLoadException(sourcePath, "$", "invalid JSON", exception);
        }
        if (!rootElement.isJsonObject()) {
            throw new EmoteLoadException(sourcePath, "$", "must be an object");
        }

        JsonObject root = rootElement.getAsJsonObject();
        EmoteJsonDocument document = new EmoteJsonDocument(sourcePath, root);
        document.requireString(root, "type", "$");
        return document;
    }

    Path sourcePath() {
        return this.sourcePath;
    }

    JsonObject root() {
        return this.root;
    }

    String type() {
        return this.root.get("type").getAsString();
    }

    EmoteMetadata metadata() throws EmoteLoadException {
        JsonObject object = requireObject(this.root, "metadata", "$");
        String name = requireString(object, "name", "$.metadata");
        if (name.isBlank()) {
            throw error("$.metadata.name", "must not be blank");
        }
        String description = requireString(object, "description", "$.metadata");
        LinkedHashMap<String, JsonElement> additional = new LinkedHashMap<>();
        object.entrySet().stream()
            .filter(entry -> !entry.getKey().equals("name") && !entry.getKey().equals("description"))
            .forEach(entry -> additional.put(entry.getKey(), entry.getValue()));
        return new EmoteMetadata(name, description, additional);
    }

    List<EmoteCallback> callbacks() throws EmoteLoadException {
        JsonArray array = optionalArray(this.root, "callbacks", "$");
        if (array == null) return List.of();
        List<EmoteCallback> callbacks = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            String path = "$.callbacks[" + index + "]";
            JsonObject callback = requireObject(array.get(index), path);
            Identifier name = requireIdentifier(requireString(callback, "name", path), path + ".name");
            String payload = callback.has("payload") ? requireString(callback, "payload", path) : "";
            callbacks.add(new EmoteCallback(name, payload));
        }
        return List.copyOf(callbacks);
    }

    EmotePlayerBehavior requirePlayer(JsonObject object, String path) throws EmoteLoadException {
        boolean hidden = requireBoolean(object, "hidden", path);
        JsonObject stopObject = requireObject(object, "stop_conditions", path);
        String stopPath = path + ".stop_conditions";
        double movementDistance = requireFiniteDouble(
            requireElement(stopObject, "movement_distance", stopPath),
            stopPath + ".movement_distance"
        );
        if (movementDistance < 0.0D) {
            throw error(stopPath + ".movement_distance", "must not be negative");
        }
        return new EmotePlayerBehavior(hidden, new EmotePlayerBehavior.StopConditions(
            movementDistance,
            requireBoolean(stopObject, "jump", stopPath),
            requireBoolean(stopObject, "submerge", stopPath),
            requireBoolean(stopObject, "ride", stopPath),
            requireBoolean(stopObject, "damage", stopPath),
            requireBoolean(stopObject, "attack", stopPath),
            requireBoolean(stopObject, "game_mode_change", stopPath)
        ));
    }

    JsonObject requireObject(JsonObject object, String key, String path) throws EmoteLoadException {
        return requireObject(requireElement(object, key, path), path + "." + key);
    }

    JsonObject requireObject(JsonElement element, String path) throws EmoteLoadException {
        if (!element.isJsonObject()) {
            throw error(path, "must be an object");
        }
        return element.getAsJsonObject();
    }

    JsonObject optionalObject(JsonObject object, String key, String path) throws EmoteLoadException {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return requireObject(element, path + "." + key);
    }

    JsonArray requireArray(JsonObject object, String key, String path) throws EmoteLoadException {
        JsonElement element = requireElement(object, key, path);
        if (!element.isJsonArray()) {
            throw error(path + "." + key, "must be an array");
        }
        return element.getAsJsonArray();
    }

    JsonArray optionalArray(JsonObject object, String key, String path) throws EmoteLoadException {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonArray()) {
            throw error(path + "." + key, "must be an array");
        }
        return element.getAsJsonArray();
    }

    JsonElement requireElement(JsonObject object, String key, String path) throws EmoteLoadException {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            throw error(path + "." + key, "is required");
        }
        return element;
    }

    String requireString(JsonObject object, String key, String path) throws EmoteLoadException {
        JsonElement element = requireElement(object, key, path);
        if (isNotString(element)) {
            throw error(path + "." + key, "must be a string");
        }
        return element.getAsString();
    }

    boolean requireBoolean(JsonObject object, String key, String path) throws EmoteLoadException {
        JsonElement element = requireElement(object, key, path);
        if (isNotBoolean(element)) {
            throw error(path + "." + key, "must be a boolean");
        }
        return element.getAsBoolean();
    }

    int requireInt(JsonObject object, String key, String path) throws EmoteLoadException {
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
        throws EmoteLoadException {
        String fieldPath = path + "." + key;
        String value = requireString(object, key, path);
        try {
            return MinecraftTime.parse(value, minimumTicks);
        } catch (IllegalArgumentException exception) {
            throw error(fieldPath, "must be a valid Minecraft time", exception);
        }
    }

    Identifier requireIdentifier(String value, String path) throws EmoteLoadException {
        int separator = value.indexOf(':');
        if (separator <= 0 || separator == value.length() - 1) {
            throw error(path, "must use namespace:path format");
        }
        Identifier id = Identifier.tryParse(value);
        if (id == null || !id.toString().equals(value)) {
            throw error(path, "must be a valid lowercase Minecraft identifier");
        }
        return id;
    }

    Node requireNode(Map<String, Node> nodes, String nodeId, String path) throws EmoteLoadException {
        Node node = nodes.get(nodeId);
        if (node == null) throw error(path, "references unknown node: " + nodeId);
        return node;
    }

    void requireExactInt(JsonObject object, String key, String path, int expected)
        throws EmoteLoadException {
        int value = requireInt(object, key, path);
        if (value != expected) {
            throw error(path + "." + key, "must equal " + expected);
        }
    }

    double requireFiniteDouble(JsonElement element, String path) throws EmoteLoadException {
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

    EmoteLoadException error(String fieldPath, String message) {
        return new EmoteLoadException(this.sourcePath, fieldPath, message);
    }

    EmoteLoadException error(String fieldPath, String message, Throwable cause) {
        return new EmoteLoadException(this.sourcePath, fieldPath, message, cause);
    }

    private boolean isNotBoolean(JsonElement element) {
        return !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean();
    }

    private boolean isNotNumber(JsonElement element) {
        return !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber();
    }

    private static EmoteLoadException tooLarge(Path sourcePath) {
        return new EmoteLoadException(
            sourcePath,
            "$",
            "file must not exceed " + MAX_JSON_BYTES + " bytes"
        );
    }
}
