package io.github.hanhy06.emote.api;

import com.google.gson.JsonElement;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record EmoteMetadata(String name, String description, Map<String, JsonElement> additional) {
    public EmoteMetadata(String name, String description) {
        this(name, description, Map.of());
    }

    public EmoteMetadata {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(additional, "additional");
        if (additional.containsKey("name") || additional.containsKey("description")) {
            throw new IllegalArgumentException("additional metadata must not contain name or description");
        }
        additional = copyAdditional(additional);
    }

    @Override
    public Map<String, JsonElement> additional() {
        return copyAdditional(this.additional);
    }

    private static Map<String, JsonElement> copyAdditional(Map<String, JsonElement> source) {
        LinkedHashMap<String, JsonElement> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(
            Objects.requireNonNull(key, "metadata key"),
            Objects.requireNonNull(value, "metadata value").deepCopy()
        ));
        return Collections.unmodifiableMap(copy);
    }
}
