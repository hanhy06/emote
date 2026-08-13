package io.github.hanhy06.emote.animation;

import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;
import io.github.hanhy06.emote.sequence.EmoteSequence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SequenceJsonLoaderTest {
    private final SequenceJsonLoader loader = new SequenceJsonLoader();

    @Test
    void loadsStepsAndDefaultsRepeatToOne(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("sit.json");
        Files.writeString(path, """
            {
              "type": "sequence",
              "schema_version": 1,
              "id": "example:sit",
              "metadata": {"name": "Sit", "description": "Sit sequence"},
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
              },
              "steps": [
                {"emote": "example:sit_down"},
                {"emote": "example:sit_idle", "repeat": 3}
              ]
            }
            """);

        EmoteSequence sequence = this.loader.load(path);

        assertEquals("example:sit", sequence.id().toString());
        assertEquals(0.1D, sequence.player().stopConditions().movementDistance());
        assertEquals(true, sequence.player().stopConditions().damage());
        assertEquals(1, sequence.steps().get(0).repeat());
        assertEquals(3, sequence.steps().get(1).repeat());
    }

    @Test
    void loadsRandomEmoteCandidates(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("random-idle.json");
        Files.writeString(path, """
            {
              "type": "sequence",
              "schema_version": 1,
              "id": "example:random_idle",
              "metadata": {"name": "Random Idle", "description": ""},
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
              },
              "steps": [
                {"emote": ["example:idle_1", "example:idle_2", "example:idle_3"], "repeat": 4}
              ]
            }
            """);

        EmoteSequence.Step step = this.loader.load(path).steps().getFirst();

        assertEquals(
            List.of("example:idle_1", "example:idle_2", "example:idle_3"),
            step.emoteIds().stream().map(Object::toString).toList()
        );
        assertEquals(4, step.repeat());
    }

    @Test
    void loadsWeightedEmoteCandidates(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("weighted-idle.json");
        Files.writeString(path, """
            {
              "type": "sequence",
              "schema_version": 1,
              "id": "example:weighted_idle",
              "metadata": {"name": "Weighted Idle", "description": ""},
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
              },
              "steps": [
                {"emote": ["example:idle_1", 30, "example:idle_2", 70], "repeat": 4}
              ]
            }
            """);

        EmoteSequence.Step step = this.loader.load(path).steps().getFirst();

        assertEquals(List.of("example:idle_1", "example:idle_2"), step.emoteIds().stream().map(Object::toString).toList());
        assertEquals(List.of(30, 70), step.choices().stream().map(EmoteSequence.Choice::chance).toList());
    }

    @Test
    void rejectsWeightedEmoteCandidatesWhoseChancesDoNotTotalOneHundred(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("invalid-weighted-idle.json");
        Files.writeString(path, """
            {
              "type": "sequence",
              "schema_version": 1,
              "id": "example:weighted_idle",
              "metadata": {"name": "Weighted Idle", "description": ""},
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
              },
              "steps": [
                {"emote": ["example:idle_1", 30, "example:idle_2", 60]}
              ]
            }
            """);

        EmoteAnimationLoadException exception = assertThrows(EmoteAnimationLoadException.class, () -> this.loader.load(path));

        assertEquals("$.steps[0].emote", exception.fieldPath());
    }

    @Test
    void rejectsEmptySteps(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("empty.json");
        Files.writeString(path, """
            {
              "type": "sequence",
              "schema_version": 1,
              "id": "example:empty",
              "metadata": {"name": "Empty", "description": ""},
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
              },
              "steps": []
            }
            """);

        assertThrows(EmoteAnimationLoadException.class, () -> this.loader.load(path));
    }

    @Test
    void requiresSequencePlayerBehavior(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("missing-player.json");
        Files.writeString(path, """
            {
              "type": "sequence",
              "schema_version": 1,
              "id": "example:missing_player",
              "metadata": {"name": "Missing Player", "description": ""},
              "steps": [{"emote": "example:wave"}]
            }
            """);

        EmoteAnimationLoadException exception = assertThrows(
            EmoteAnimationLoadException.class,
            () -> this.loader.load(path)
        );
        assertEquals("$.player", exception.fieldPath());
    }
}
