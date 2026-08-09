package io.github.hanhy06.emote.animation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class AnimationJsonReader {
    private final Path sourcePath;

    AnimationJsonReader(Path sourcePath) {
        this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
    }

    EmoteAnimation.Matrix requireMatrix(JsonObject object, String key, String path)
        throws EmoteAnimationLoadException {
        return readMatrix(requireArray(object, key, path), path + "." + key);
    }

    EmoteAnimation.Matrix readMatrix(JsonArray array, String path) throws EmoteAnimationLoadException {
        if (array.size() != 16) {
            throw error(path, "must contain 16 values");
        }
        List<Double> values = new ArrayList<>(16);
        for (int index = 0; index < array.size(); index++) {
            values.add(requireFiniteDouble(array.get(index), path + "[" + index + "]"));
        }
        return new EmoteAnimation.Matrix(values);
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
}
