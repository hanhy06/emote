package io.github.hanhy06.emote.animation;

import com.google.gson.JsonPrimitive;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;
import io.github.hanhy06.emote.sequence.EmoteSequence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SequenceJsonLoaderTest {
    private final SequenceJsonLoader loader = new SequenceJsonLoader();

    @Test
    void loadsSchemaTwoSettingsMetadataEmotesAndWait(@TempDir Path tempDir) throws Exception {
        EmoteSequence sequence = load(tempDir, "sit.json", baseJson("""
            {"emote": "example:sit_down"},
            {"wait": "0.5s"},
            {"emote": "example:sit_idle", "repeat": 3}
            """));

        assertEquals("example:sit", sequence.id().toString());
        assertEquals(new JsonPrimitive("author"), sequence.metadata().additional().get("credit"));
        assertEquals(100, sequence.settings().cooldownTicks());
        assertEquals(0.1D, sequence.settings().player().stopConditions().movementDistance());
        assertEquals(1, ((EmoteSequence.EmoteStep) sequence.steps().get(0)).repeat());
        assertEquals(10, ((EmoteSequence.WaitStep) sequence.steps().get(1)).ticks());
        assertEquals(3, ((EmoteSequence.EmoteStep) sequence.steps().get(2)).repeat());
    }

    @Test
    void loadsEqualAndWeightedEmoteCandidates(@TempDir Path tempDir) throws Exception {
        EmoteSequence equal = load(tempDir, "equal.json", baseJson("""
            {"emote": ["example:idle_1", "example:idle_2", "example:idle_3"], "repeat": 4}
            """));
        EmoteSequence weighted = load(tempDir, "weighted.json", baseJson("""
            {"emote": ["example:idle_1", 30, "example:idle_2", 70], "repeat": 4}
            """));

        EmoteSequence.EmoteStep equalStep = assertInstanceOf(EmoteSequence.EmoteStep.class, equal.steps().getFirst());
        EmoteSequence.EmoteStep weightedStep = assertInstanceOf(EmoteSequence.EmoteStep.class, weighted.steps().getFirst());
        assertEquals(List.of("example:idle_1", "example:idle_2", "example:idle_3"), equalStep.emoteIds().stream().map(Object::toString).toList());
        assertEquals(List.of(30, 70), weightedStep.choices().stream().map(EmoteSequence.Choice::chance).toList());
    }

    @Test
    void rejectsInvalidWeightedCandidates(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("invalid-weighted.json");
        Files.writeString(path, baseJson("""
            {"emote": ["example:idle_1", 30, "example:idle_2", 60]}
            """));

        EmoteAnimationLoadException exception = assertThrows(EmoteAnimationLoadException.class, () -> this.loader.load(path));
        assertEquals("$.steps[0].emote", exception.fieldPath());
    }

    @Test
    void rejectsWaitAtEdgesOrConsecutiveWaits(@TempDir Path tempDir) throws Exception {
        Path first = tempDir.resolve("first-wait.json");
        Path last = tempDir.resolve("last-wait.json");
        Path consecutive = tempDir.resolve("consecutive-wait.json");
        Files.writeString(first, baseJson("{\"wait\":\"1t\"}, {\"emote\":\"example:idle\"}"));
        Files.writeString(last, baseJson("{\"emote\":\"example:idle\"}, {\"wait\":\"1t\"}"));
        Files.writeString(consecutive, baseJson("{\"emote\":\"example:idle\"}, {\"wait\":\"1t\"}, {\"wait\":\"2t\"}, {\"emote\":\"example:end\"}"));

        assertEquals("$.steps[0].wait", assertThrows(EmoteAnimationLoadException.class, () -> this.loader.load(first)).fieldPath());
        assertEquals("$.steps[1].wait", assertThrows(EmoteAnimationLoadException.class, () -> this.loader.load(last)).fieldPath());
        assertEquals("$.steps[2].wait", assertThrows(EmoteAnimationLoadException.class, () -> this.loader.load(consecutive)).fieldPath());
    }

    @Test
    void requiresSequencePlayerBehavior(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("missing-player.json");
        Files.writeString(path, baseJson("{\"emote\":\"example:wave\"}").replace(playerJson(), "\"player\": null"));

        EmoteAnimationLoadException exception = assertThrows(EmoteAnimationLoadException.class, () -> this.loader.load(path));
        assertEquals("$.settings.player", exception.fieldPath());
    }

    private EmoteSequence load(Path tempDir, String fileName, String json) throws Exception {
        Path path = tempDir.resolve(fileName);
        Files.writeString(path, json);
        return this.loader.load(path);
    }

    private static String baseJson(String steps) {
        return """
            {
              "type": "sequence",
              "schema_version": 2,
              "id": "example:sit",
              "metadata": {"name": "Sit", "description": "Sit sequence", "credit": "author"},
              "settings": {
                "cooldown": "5s",
                %s
              },
              "steps": [%s]
            }
            """.formatted(playerJson(), steps);
    }

    private static String playerJson() {
        return """
            "player": {
              "hidden": true,
              "stop_conditions": {
                "movement_distance": 0.1,
                "jump": true,
                "submerge": true,
                "ride": true,
                "damage": true,
                "attack": true,
                "game_mode_change": true
              }
            }
            """;
    }
}
