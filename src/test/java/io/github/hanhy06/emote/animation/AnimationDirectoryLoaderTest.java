package io.github.hanhy06.emote.animation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationDirectoryLoaderTest {
    private static final Path REFERENCE_PATH = Path.of("docs/emote-animation-format.json");
    private final AnimationDirectoryLoader loader = new AnimationDirectoryLoader();

    @Test
    void loadsMultipleJsonFilesInIdOrder(@TempDir Path tempDir) throws Exception {
        writeAnimation(tempDir.resolve("z.json"), "zeta:wave");
        Path nestedDirectory = Files.createDirectories(tempDir.resolve("nested/deeper"));
        writeAnimation(nestedDirectory.resolve("a.json"), "alpha:wave");
        Files.writeString(tempDir.resolve("notes.txt"), "ignored");

        List<EmoteAnimation.Loaded> loaded = this.loader.load(tempDir, animation -> animation).animations();

        assertEquals(List.of("alpha:wave", "zeta:wave"), loaded.stream()
            .map(animation -> animation.animation().id().toString())
            .toList());
    }

    @Test
    void rejectsEveryFileSharingDuplicateId(@TempDir Path tempDir) throws Exception {
        writeAnimation(tempDir.resolve("first.json"), "same:wave");
        writeAnimation(tempDir.resolve("second.json"), "same:wave");
        writeAnimation(tempDir.resolve("valid.json"), "other:wave");

        List<EmoteAnimation.Loaded> loaded = this.loader.load(tempDir, animation -> animation).animations();

        assertEquals(List.of("other:wave"), loaded.stream()
            .map(animation -> animation.animation().id().toString())
            .toList());
    }

    @Test
    void skipsInvalidFileWithoutDiscardingValidFiles(@TempDir Path tempDir) throws Exception {
        writeAnimation(tempDir.resolve("valid.json"), "valid:wave");
        Files.writeString(tempDir.resolve("broken.json"), "{");

        List<EmoteAnimation.Loaded> loaded = this.loader.load(tempDir, animation -> animation).animations();

        assertEquals(1, loaded.size());
        assertEquals("valid:wave", loaded.getFirst().animation().id().toString());
    }

    @Test
    void createsMissingAnimationDirectory(@TempDir Path tempDir) {
        Path directory = tempDir.resolve("animations");

        assertTrue(this.loader.load(directory, animation -> animation).animations().isEmpty());
        assertTrue(Files.isDirectory(directory));
    }

    @Test
    void separatesSequenceFilesFromAnimations(@TempDir Path tempDir) throws Exception {
        writeAnimation(tempDir.resolve("clips/sit.json"), "example:sit_down");
        Files.writeString(tempDir.resolve("sequence.json"), """
            {
              "type": "sequence",
              "schema_version": 3,
              "id": "example:sit",
              "metadata": {"name": "Sit", "description": "Sit sequence"},
              "settings": {
                "cooldown": "0t",
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
              },
              "steps": [{"emote": "example:sit_down"}]
            }
            """);

        AnimationDirectoryLoader.DirectoryContents contents = this.loader.load(
            tempDir,
            animation -> animation
        );

        assertEquals(List.of("example:sit_down"), contents.animations().stream()
            .map(animation -> animation.animation().id().toString())
            .toList());
        assertEquals(List.of("example:sit"), contents.sequences().stream()
            .map(sequence -> sequence.id().toString())
            .toList());
    }

    private void writeAnimation(Path path, String id) throws IOException {
        JsonObject root = JsonParser
            .parseString(Files.readString(REFERENCE_PATH))
            .getAsJsonObject();

        root.addProperty("id", id);

        Files.createDirectories(path.getParent());
        Files.writeString(path, root.toString());
    }
}
