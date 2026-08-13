package io.github.hanhy06.emote.animation;

import com.google.gson.*;
import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;
import io.github.hanhy06.emote.emote.EmoteSequence;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class SequenceJsonLoader {
    private static final int SCHEMA_VERSION = 3;
    private final ParticipantPlacementParser placementParser = new ParticipantPlacementParser();

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
        EmoteSequence.Participants participants = parseParticipants(root, reader);
        JsonObject settingsObject = reader.requireObject(root, "settings", "$");
        int cooldownTicks = reader.requireTime(settingsObject, "cooldown", "$.settings", 0);
        EmotePlayerBehavior player = AnimationJsonLoader.parsePlayer(
            reader.requireObject(settingsObject, "player", "$.settings"),
            "$.settings.player",
            reader
        );
        EmoteSequence.Settings settings = new EmoteSequence.Settings(cooldownTicks, player);

        List<EmoteSequence.Step> steps = parseSteps(reader.requireArray(root, "steps", "$"), "$.steps", true, reader);
        try {
            return new EmoteSequence(sourcePath, id, metadata, settings, participants, steps);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw reader.error("$.steps", exception.getMessage(), exception);
        }
    }

    private EmoteSequence.Participants parseParticipants(JsonObject root, EmoteJsonReader reader)
        throws EmoteAnimationLoadException {
        JsonElement element = root.get("participants");
        if (element == null || element.isJsonNull()) {
            return null;
        }
        JsonObject participants = reader.requireObject(element, "$.participants");
        return new EmoteSequence.Participants(
            parseParticipant(participants, "initiator", reader),
            parseParticipant(participants, "partner", reader)
        );
    }

    private EmoteSequence.ParticipantPlacement parseParticipant(
        JsonObject participants,
        String role,
        EmoteJsonReader reader
    ) throws EmoteAnimationLoadException {
        String path = "$.participants." + role;
        JsonObject placement = reader.requireObject(participants, role, "$.participants");
        return this.placementParser.parse(
            reader.requireString(placement, "position", path),
            reader.requireString(placement, "rotation", path),
            path,
            reader
        );
    }

    private List<EmoteSequence.Step> parseSteps(
        JsonArray stepsArray,
        String stepsPath,
        boolean allowAwaitPartner,
        EmoteJsonReader reader
    ) throws EmoteAnimationLoadException {
        if (stepsArray.isEmpty()) {
            throw reader.error(stepsPath, "must not be empty");
        }
        List<EmoteSequence.Step> steps = new ArrayList<>(stepsArray.size());
        for (int index = 0; index < stepsArray.size(); index++) {
            String path = stepsPath + "[" + index + "]";
            JsonObject stepObject = reader.requireObject(stepsArray.get(index), path);
            boolean hasEmote = stepObject.has("emote") && !stepObject.get("emote").isJsonNull();
            boolean hasWait = stepObject.has("wait") && !stepObject.get("wait").isJsonNull();
            boolean hasAwaitPartner = stepObject.has("await_partner") && !stepObject.get("await_partner").isJsonNull();
            if ((hasEmote ? 1 : 0) + (hasWait ? 1 : 0) + (hasAwaitPartner ? 1 : 0) != 1) {
                throw reader.error(path, "must contain exactly one of emote, wait, or await_partner");
            }
            if (hasAwaitPartner) {
                if (!allowAwaitPartner) {
                    throw reader.error(path + ".await_partner", "is not supported inside a collaboration branch");
                }
                steps.add(parseAwaitPartner(stepObject, path, reader));
                continue;
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
        return List.copyOf(steps);
    }

    private EmoteSequence.AwaitPartnerStep parseAwaitPartner(
        JsonObject stepObject,
        String path,
        EmoteJsonReader reader
    ) throws EmoteAnimationLoadException {
        if (stepObject.has("repeat")) {
            throw reader.error(path + ".repeat", "is not supported on an await_partner step");
        }
        JsonObject await = reader.requireObject(stepObject, "await_partner", path);
        Identifier offer = parseId(
            reader.requireString(await, "emote", path + ".await_partner"),
            path + ".await_partner.emote",
            reader
        );
        int timeoutTicks = reader.requireTime(await, "timeout", path + ".await_partner", 1);
        List<EmoteSequence.Step> matched = parseSteps(
            reader.requireArray(stepObject, "matched", path),
            path + ".matched",
            false,
            reader
        );
        List<EmoteSequence.Step> timeout = parseSteps(
            reader.requireArray(stepObject, "timeout", path),
            path + ".timeout",
            false,
            reader
        );
        return new EmoteSequence.AwaitPartnerStep(offer, timeoutTicks, matched, timeout);
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
