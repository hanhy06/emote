package io.github.hanhy06.emote.animation;

import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;
import io.github.hanhy06.emote.sequence.EmoteSequence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

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
