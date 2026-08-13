package io.github.hanhy06.emote.animation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;
import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.sequence.EmoteSequence;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class SequenceJsonLoader {
    private static final int SCHEMA_VERSION = 2;

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
        if (!reader.requireString(root, "type", "$").equals("sequence")) {
            throw reader.error("$.type", "must equal sequence");
        }
        reader.requireExactInt(root, "schema_version", "$", SCHEMA_VERSION);
        Identifier id = parseId(reader.requireString(root, "id", "$"), "$.id", reader);
        EmoteMetadata metadata = AnimationJsonLoader.parseMetadata(reader.requireObject(root, "metadata", "$"), reader);
        JsonObject settingsObject = reader.requireObject(root, "settings", "$");
        int cooldownTicks = reader.requireTime(settingsObject, "cooldown", "$.settings", 0);
        EmotePlayerBehavior player = AnimationJsonLoader.parsePlayer(
            reader.requireObject(settingsObject, "player", "$.settings"),
            "$.settings.player",
            reader
        );
        EmoteSequence.Settings settings = new EmoteSequence.Settings(cooldownTicks, player);

        var stepsArray = reader.requireArray(root, "steps", "$");
        if (stepsArray.isEmpty()) {
            throw reader.error("$.steps", "must not be empty");
        }
        List<EmoteSequence.Step> steps = new ArrayList<>(stepsArray.size());
        for (int index = 0; index < stepsArray.size(); index++) {
            String path = "$.steps[" + index + "]";
            JsonObject stepObject = reader.requireObject(stepsArray.get(index), path);
            boolean hasEmote = stepObject.has("emote") && !stepObject.get("emote").isJsonNull();
            boolean hasWait = stepObject.has("wait") && !stepObject.get("wait").isJsonNull();
            if (hasEmote == hasWait) {
                throw reader.error(path, "must contain exactly one of emote or wait");
            }
            if (hasWait) {
                if (stepObject.has("repeat")) {
                    throw reader.error(path + ".repeat", "is not supported on a wait step");
                }
                if (index == 0 || index == stepsArray.size() - 1) {
                    throw reader.error(path + ".wait", "must be between emote steps");
                }
                if (!steps.isEmpty() && steps.getLast() instanceof EmoteSequence.WaitStep) {
                    throw reader.error(path + ".wait", "must not follow another wait step");
                }
                steps.add(new EmoteSequence.WaitStep(reader.requireTime(stepObject, "wait", path, 1)));
                continue;
            }
            List<EmoteSequence.Choice> choices = readEmoteChoices(stepObject, path, reader);
            int repeat = stepObject.has("repeat")
                ? reader.requireInt(stepObject, "repeat", path)
                : 1;
            if (repeat < 1) {
                throw reader.error(path + ".repeat", "must be at least 1");
            }
            steps.add(new EmoteSequence.EmoteStep(choices, repeat));
        }
        return new EmoteSequence(sourcePath, id, metadata, settings, steps);
    }

    private List<EmoteSequence.Choice> readEmoteChoices(JsonObject stepObject, String path, EmoteJsonReader reader)
        throws EmoteAnimationLoadException {
        JsonElement element = reader.requireElement(stepObject, "emote", path);
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return List.of(new EmoteSequence.Choice(parseId(element.getAsString(), path + ".emote", reader), 0));
        }
        if (!element.isJsonArray()) {
            throw reader.error(path + ".emote", "must be a string or a non-empty array of strings");
        }

        JsonArray array = element.getAsJsonArray();
        if (array.isEmpty()) {
            throw reader.error(path + ".emote", "must not be empty");
        }
        boolean weighted = array.size() > 1
            && array.get(1).isJsonPrimitive()
            && array.get(1).getAsJsonPrimitive().isNumber();
        int stride = weighted ? 2 : 1;
        if (weighted && array.size() % 2 != 0) {
            throw reader.error(path + ".emote", "must contain complete id and chance pairs");
        }

        List<EmoteSequence.Choice> choices = new ArrayList<>(weighted ? array.size() / 2 : array.size());
        int totalChance = 0;
        for (int index = 0; index < array.size(); index += stride) {
            JsonElement candidate = array.get(index);
            String candidatePath = path + ".emote[" + index + "]";
            if (reader.isNotString(candidate)) {
                throw reader.error(candidatePath, "must be a string");
            }
            Identifier emoteId = parseId(candidate.getAsString(), candidatePath, reader);
            if (choices.stream().anyMatch(choice -> choice.emoteId().equals(emoteId))) {
                throw reader.error(candidatePath, "must not duplicate an earlier candidate");
            }
            int chance = 0;
            if (weighted) {
                chance = reader.requireFiniteDouble(array.get(index + 1), path + ".emote[" + (index + 1) + "]") % 1.0D == 0.0D
                    ? array.get(index + 1).getAsInt()
                    : -1;
                if (chance < 1 || chance > 100) {
                    throw reader.error(path + ".emote[" + (index + 1) + "]", "must be an integer between 1 and 100");
                }
                totalChance += chance;
            }
            choices.add(new EmoteSequence.Choice(emoteId, chance));
        }
        if (weighted && totalChance != 100) {
            throw reader.error(path + ".emote", "chances must total 100");
        }
        return choices;
    }

    private Identifier parseId(String value, String path, EmoteJsonReader reader)
        throws EmoteAnimationLoadException {
        Identifier id = Identifier.tryParse(value);
        if (id == null || !id.toString().equals(value) || value.indexOf(':') <= 0) {
            throw reader.error(path, "must be a valid lowercase namespace:path identifier");
        }
        return id;
    }
}
