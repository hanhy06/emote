package io.github.hanhy06.emote.animation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.sequence.EmoteSequence;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class SequenceJsonLoader {
    private static final int SCHEMA_VERSION = 1;

    public EmoteSequence load(Path sourcePath) throws EmoteAnimationLoadException {
        byte[] bytes;
        try {
            long fileSize = Files.size(sourcePath);
            if (fileSize > AnimationJsonLoader.MAX_JSON_BYTES) {
                throw new EmoteAnimationLoadException(
                    sourcePath,
                    "$",
                    "file must not exceed " + AnimationJsonLoader.MAX_JSON_BYTES + " bytes"
                );
            }
            bytes = Files.readAllBytes(sourcePath);
        } catch (IOException exception) {
            throw new EmoteAnimationLoadException(sourcePath, "$", "failed to read file", exception);
        }

        AnimationJsonReader reader = new AnimationJsonReader(sourcePath);
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
        if (!reader.requireString(root, "type", "$").equals("sequence")) {
            throw reader.error("$.type", "must equal sequence");
        }
        reader.requireExactInt(root, "schema_version", "$", SCHEMA_VERSION);
        Identifier id = parseId(reader.requireString(root, "id", "$"), "$.id", reader);
        JsonObject metadataObject = reader.requireObject(root, "metadata", "$");
        String name = reader.requireString(metadataObject, "name", "$.metadata");
        if (name.isBlank()) {
            throw reader.error("$.metadata.name", "must not be blank");
        }
        EmoteSequence.Metadata metadata = new EmoteSequence.Metadata(
            name,
            reader.requireString(metadataObject, "description", "$.metadata")
        );
        EmoteAnimation.PlayerBehavior player = AnimationJsonLoader.parsePlayer(
            reader.requireObject(root, "player", "$"),
            reader
        );

        var stepsArray = reader.requireArray(root, "steps", "$");
        if (stepsArray.isEmpty()) {
            throw reader.error("$.steps", "must not be empty");
        }
        List<EmoteSequence.Step> steps = new ArrayList<>(stepsArray.size());
        for (int index = 0; index < stepsArray.size(); index++) {
            String path = "$.steps[" + index + "]";
            JsonObject stepObject = reader.requireObject(stepsArray.get(index), path);
            Identifier emoteId = parseId(reader.requireString(stepObject, "emote", path), path + ".emote", reader);
            int repeat = stepObject.has("repeat")
                ? reader.requireInt(stepObject, "repeat", path)
                : 1;
            if (repeat < 1) {
                throw reader.error(path + ".repeat", "must be at least 1");
            }
            steps.add(new EmoteSequence.Step(emoteId, repeat));
        }
        return new EmoteSequence(sourcePath, id, metadata, player, steps);
    }

    private Identifier parseId(String value, String path, AnimationJsonReader reader)
        throws EmoteAnimationLoadException {
        Identifier id = Identifier.tryParse(value);
        if (id == null || !id.toString().equals(value) || value.indexOf(':') <= 0) {
            throw reader.error(path, "must be a valid lowercase namespace:path identifier");
        }
        return id;
    }
}
