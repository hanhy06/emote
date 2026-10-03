package io.github.hanhy06.emote.content.loader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;
import io.github.hanhy06.emote.api.sequence.EmoteSequence;
import io.github.hanhy06.emote.content.LoadedSequence;
import net.minecraft.resources.Identifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class SequenceJsonParser {
    private static final int SCHEMA_VERSION = 4;

    public LoadedSequence parse(Path sourcePath) throws EmoteAnimationLoadException {
        return parse(EmoteJsonDocument.read(sourcePath));
    }

    LoadedSequence parse(EmoteJsonDocument document) throws EmoteAnimationLoadException {
        JsonObject root = document.root();
        if (!document.type().equals("sequence")) {
            throw document.error("$.type", "must equal sequence");
        }
        document.requireExactInt(root, "schema_version", "$", SCHEMA_VERSION);
        Identifier id = document.requireIdentifier(document.requireString(root, "id", "$"), "$.id");
        EmoteMetadata metadata = AnimationJsonParser.parseMetadata(document.requireObject(root, "metadata", "$"), document);
        if (root.has("participants") && !root.get("participants").isJsonNull()) {
            throw document.error("$.participants", "two-player matching is no longer supported");
        }
        JsonObject settingsObject = document.requireObject(root, "settings", "$");
        int cooldownTicks = document.requireTime(settingsObject, "cooldown", "$.settings", 0);
        EmotePlayerBehavior player = AnimationJsonParser.parsePlayer(
            document.requireObject(settingsObject, "player", "$.settings"),
            "$.settings.player",
            document
        );
        EmoteSequence.Settings settings = new EmoteSequence.Settings(cooldownTicks, player);

        List<EmoteSequence.Step> steps = parseSteps(document.requireArray(root, "steps", "$"), "$.steps", document);
        try {
            return new LoadedSequence(document.sourcePath(), new EmoteSequence(id, metadata, settings, steps, AnimationJsonParser.parseCallbacks(root, document)));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw document.error("$.steps", exception.getMessage(), exception);
        }
    }

    private List<EmoteSequence.Step> parseSteps(
        JsonArray stepsArray,
        String stepsPath,
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
            if (stepObject.has("await_partner")) {
                throw document.error(path + ".await_partner", "two-player matching is no longer supported");
            }
            if (hasEmote == hasWait) {
                throw document.error(path, "must contain exactly one of emote or wait");
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
            steps.add(new EmoteSequence.AnimationStep(choices, repeat, transitionTicks));
        }
        return List.copyOf(steps);
    }

    private void rejectTransition(JsonObject stepObject, String path, EmoteJsonDocument document)
        throws EmoteAnimationLoadException {
        if (stepObject.has("transition")) {
            throw document.error(path + ".transition", "is supported only on an emote step");
        }
    }

    private List<EmoteSequence.Choice> readEmoteChoices(JsonObject stepObject, String path, EmoteJsonDocument document)
        throws EmoteAnimationLoadException {
        JsonElement element = document.requireElement(stepObject, "emote", path);
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return List.of(new EmoteSequence.Choice(document.requireIdentifier(element.getAsString(), path + ".emote"), 0));
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
            Identifier targetId = document.requireIdentifier(candidate.getAsString(), candidatePath);
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

}
