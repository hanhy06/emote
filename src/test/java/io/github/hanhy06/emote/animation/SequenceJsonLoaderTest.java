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
              "steps": [
                {"emote": "example:sit_down"},
                {"emote": "example:sit_idle", "repeat": 3}
              ]
            }
            """);

        EmoteSequence sequence = this.loader.load(path);

        assertEquals("example:sit", sequence.id().toString());
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
              "steps": []
            }
            """);

        assertThrows(EmoteAnimationLoadException.class, () -> this.loader.load(path));
    }
}
