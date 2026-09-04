package io.github.hanhy06.emote.content.loader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;
import io.github.hanhy06.emote.content.EmoteSequence;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.commands.arguments.coordinates.RotationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.resources.Identifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class SequenceJsonParser {
    private static final int SCHEMA_VERSION = 4;

    public EmoteSequence parse(Path sourcePath) throws EmoteAnimationLoadException {
        return parse(EmoteJsonDocument.read(sourcePath));
    }

    EmoteSequence parse(EmoteJsonDocument document) throws EmoteAnimationLoadException {
        JsonObject root = document.root();
        if (!document.type().equals("sequence")) {
            throw document.error("$.type", "must equal sequence");
        }
        document.requireExactInt(root, "schema_version", "$", SCHEMA_VERSION);
        Identifier id = parseId(document.requireString(root, "id", "$"), "$.id", document);
        EmoteMetadata metadata = AnimationJsonParser.parseMetadata(document.requireObject(root, "metadata", "$"), document);
        EmoteSequence.Participants participants = parseParticipants(root, document);
        JsonObject settingsObject = document.requireObject(root, "settings", "$");
        int cooldownTicks = document.requireTime(settingsObject, "cooldown", "$.settings", 0);
        EmotePlayerBehavior player = AnimationJsonParser.parsePlayer(
            document.requireObject(settingsObject, "player", "$.settings"),
            "$.settings.player",
            document
        );
        EmoteSequence.Settings settings = new EmoteSequence.Settings(cooldownTicks, player);

        List<EmoteSequence.Step> steps = parseSteps(document.requireArray(root, "steps", "$"), "$.steps", true, document);
        try {
            return new EmoteSequence(document.sourcePath(), id, metadata, settings, participants, steps);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw document.error("$.steps", exception.getMessage(), exception);
        }
    }

    private EmoteSequence.Participants parseParticipants(JsonObject root, EmoteJsonDocument document)
        throws EmoteAnimationLoadException {
        JsonElement element = root.get("participants");
        if (element == null || element.isJsonNull()) {
            return null;
        }
        JsonObject participants = document.requireObject(element, "$.participants");
        return new EmoteSequence.Participants(
            parseParticipant(participants, "initiator", document),
            parseParticipant(participants, "partner", document)
        );
    }

    private EmoteSequence.ParticipantPlacement parseParticipant(
        JsonObject participants,
        String role,
        EmoteJsonDocument document
    ) throws EmoteAnimationLoadException {
        String path = "$.participants." + role;
        JsonObject placement = document.requireObject(participants, role, "$.participants");
        Coordinates position = parseCoordinates(
            document.requireString(placement, "position", path),
            path + ".position",
            true,
            document
        );
        if (!position.isXRelative() || !position.isYRelative() || !position.isZRelative()) {
            throw document.error(path + ".position", "must use only relative ~ or local ^ coordinates");
        }
        Coordinates rotation = parseCoordinates(
            document.requireString(placement, "rotation", path),
            path + ".rotation",
            false,
            document
        );
        return new EmoteSequence.ParticipantPlacement(position, rotation);
    }

    private Coordinates parseCoordinates(
        String value,
        String path,
        boolean position,
        EmoteJsonDocument document
    ) throws EmoteAnimationLoadException {
        StringReader stringReader = new StringReader(value);
        try {
            Coordinates coordinates = position
                ? Vec3Argument.vec3(false).parse(stringReader)
                : RotationArgument.rotation().parse(stringReader);
            if (stringReader.canRead()) {
                throw document.error(path, "contains trailing input");
            }
            return coordinates;
        } catch (CommandSyntaxException exception) {
            throw document.error(path, "invalid Minecraft coordinates", exception);
        }
    }

    private List<EmoteSequence.Step> parseSteps(
        JsonArray stepsArray,
        String stepsPath,
        boolean allowAwaitPartner,
        EmoteJsonDocument document
    ) throws EmoteAnimationLoadException {
        if (stepsArray.isEmpty()) {
            throw document.error(stepsPath, "must not be empty");
        }
        List<EmoteSequence.Step> steps = new ArrayList<>(stepsArray.size());
        for (int index = 0; index < stepsArray.size(); index++) {
            String path = stepsPath + "[" + index + "]";
            JsonObject stepObject = document.requireObject(stepsArray.get(index), path);
            boolean hasEmote = stepObject.has("emote") && !stepObject.get("emote").isJsonNull();
            boolean hasWait = stepObject.has("wait") && !stepObject.get("wait").isJsonNull();
            boolean hasAwaitPartner = stepObject.has("await_partner") && !stepObject.get("await_partner").isJsonNull();
            if ((hasEmote ? 1 : 0) + (hasWait ? 1 : 0) + (hasAwaitPartner ? 1 : 0) != 1) {
                throw document.error(path, "must contain exactly one of emote, wait, or await_partner");
            }
            if (hasAwaitPartner) {
                rejectTransition(stepObject, path, document);
                if (!allowAwaitPartner) {
                    throw document.error(path + ".await_partner", "is not supported inside a collaboration branch");
                }
                steps.add(parseAwaitPartner(stepObject, path, document));
                continue;
            }
            if (hasWait) {
                rejectTransition(stepObject, path, document);
                if (stepObject.has("repeat")) {
                    throw document.error(path + ".repeat", "is not supported on a wait step");
                }
                if (index == 0 || index == stepsArray.size() - 1) {
                    throw document.error(path + ".wait", "must be between emote steps");
                }
                if (!steps.isEmpty() && steps.getLast() instanceof EmoteSequence.WaitStep) {
                    throw document.error(path + ".wait", "must not follow another wait step");
                }
                steps.add(new EmoteSequence.WaitStep(document.requireTime(stepObject, "wait", path, 1)));
                continue;
            }
            List<EmoteSequence.Choice> choices = readEmoteChoices(stepObject, path, document);
            int repeat = stepObject.has("repeat")
                ? document.requireInt(stepObject, "repeat", path)
                : 1;
            if (repeat < 1) {
                throw document.error(path + ".repeat", "must be at least 1");
            }
            int transitionTicks = stepObject.has("transition")
                ? document.requireTime(stepObject, "transition", path, 0)
                : 0;
            steps.add(new EmoteSequence.EmoteStep(choices, repeat, transitionTicks));
        }
        return List.copyOf(steps);
    }

    private void rejectTransition(JsonObject stepObject, String path, EmoteJsonDocument document)
        throws EmoteAnimationLoadException {
        if (stepObject.has("transition")) {
            throw document.error(path + ".transition", "is supported only on an emote step");
        }
    }

    private EmoteSequence.AwaitPartnerStep parseAwaitPartner(
        JsonObject stepObject,
        String path,
        EmoteJsonDocument document
    ) throws EmoteAnimationLoadException {
        if (stepObject.has("repeat")) {
            throw document.error(path + ".repeat", "is not supported on an await_partner step");
        }
        JsonObject await = document.requireObject(stepObject, "await_partner", path);
        Identifier offer = parseId(
            document.requireString(await, "emote", path + ".await_partner"),
            path + ".await_partner.emote",
            document
        );
        if (EmoteSequence.Control.fromId(offer) != null) {
            throw document.error(path + ".await_partner.emote", "must reference an animation");
        }
        int timeoutTicks = document.requireTime(await, "timeout", path + ".await_partner", 1);
        List<EmoteSequence.Step> matched = parseSteps(
            document.requireArray(stepObject, "matched", path),
            path + ".matched",
            false,
            document
        );
        List<EmoteSequence.Step> timeout = parseSteps(
            document.requireArray(stepObject, "timeout", path),
            path + ".timeout",
            false,
            document
        );
        return new EmoteSequence.AwaitPartnerStep(offer, timeoutTicks, matched, timeout);
    }

    private List<EmoteSequence.Choice> readEmoteChoices(JsonObject stepObject, String path, EmoteJsonDocument document)
        throws EmoteAnimationLoadException {
        JsonElement element = document.requireElement(stepObject, "emote", path);
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return List.of(new EmoteSequence.Choice(parseId(element.getAsString(), path + ".emote", document), 0));
        }
        if (!element.isJsonArray()) {
            throw document.error(path + ".emote", "must be a string or a non-empty array of strings");
        }

        JsonArray array = element.getAsJsonArray();
        if (array.isEmpty()) {
            throw document.error(path + ".emote", "must not be empty");
        }
        boolean weighted = array.size() > 1
            && array.get(1).isJsonPrimitive()
            && array.get(1).getAsJsonPrimitive().isNumber();
        int stride = weighted ? 2 : 1;
        if (weighted && array.size() % 2 != 0) {
            throw document.error(path + ".emote", "must contain complete id and chance pairs");
        }

        List<EmoteSequence.Choice> choices = new ArrayList<>(weighted ? array.size() / 2 : array.size());
        int totalChance = 0;
        for (int index = 0; index < array.size(); index += stride) {
            JsonElement candidate = array.get(index);
            String candidatePath = path + ".emote[" + index + "]";
            if (document.isNotString(candidate)) {
                throw document.error(candidatePath, "must be a string");
            }
            Identifier targetId = parseId(candidate.getAsString(), candidatePath, document);
            if (choices.stream().anyMatch(choice -> choice.targetId().equals(targetId))) {
                throw document.error(candidatePath, "must not duplicate an earlier candidate");
            }
            int chance = 0;
            if (weighted) {
                chance = document.requireFiniteDouble(array.get(index + 1), path + ".emote[" + (index + 1) + "]") % 1.0D == 0.0D
                    ? array.get(index + 1).getAsInt()
                    : -1;
                if (chance < 1 || chance > 100) {
                    throw document.error(path + ".emote[" + (index + 1) + "]", "must be an integer between 1 and 100");
                }
                totalChance += chance;
            }
            choices.add(new EmoteSequence.Choice(targetId, chance));
        }
        if (weighted && totalChance != 100) {
            throw document.error(path + ".emote", "chances must total 100");
        }
        return choices;
    }

    private Identifier parseId(String value, String path, EmoteJsonDocument document)
        throws EmoteAnimationLoadException {
        Identifier id = Identifier.tryParse(value);
        if (id == null || !id.toString().equals(value) || value.indexOf(':') <= 0) {
            throw document.error(path, "must be a valid lowercase namespace:path identifier");
        }
        return id;
    }
}
