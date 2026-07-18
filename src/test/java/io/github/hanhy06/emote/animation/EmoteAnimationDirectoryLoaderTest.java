package io.github.hanhy06.emote.animation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmoteAnimationDirectoryLoaderTest {
    private static final Path REFERENCE_PATH = Path.of("docs/emote-animation-format.json");
    private final EmoteAnimationDirectoryLoader loader = new EmoteAnimationDirectoryLoader();

    @Test
    void loadsMultipleJsonFilesInIdOrder(@TempDir Path tempDir) throws Exception {
        writeAnimation(tempDir.resolve("z.json"), "zeta:wave");
        writeAnimation(tempDir.resolve("a.json"), "alpha:wave");
        Files.writeString(tempDir.resolve("notes.txt"), "ignored");

        List<EmoteAnimation.Loaded> loaded = this.loader.load(tempDir, "26.2", animation -> animation);

        assertEquals(List.of("alpha:wave", "zeta:wave"), loaded.stream()
            .map(animation -> animation.animation().id().toString())
            .toList());
    }

    @Test
    void rejectsEveryFileSharingDuplicateId(@TempDir Path tempDir) throws Exception {
        writeAnimation(tempDir.resolve("first.json"), "same:wave");
        writeAnimation(tempDir.resolve("second.json"), "same:wave");
        writeAnimation(tempDir.resolve("valid.json"), "other:wave");

        List<EmoteAnimation.Loaded> loaded = this.loader.load(tempDir, "26.2", animation -> animation);

        assertEquals(List.of("other:wave"), loaded.stream()
            .map(animation -> animation.animation().id().toString())
            .toList());
    }

    @Test
    void skipsInvalidFileWithoutDiscardingValidFiles(@TempDir Path tempDir) throws Exception {
        writeAnimation(tempDir.resolve("valid.json"), "valid:wave");
        Files.writeString(tempDir.resolve("broken.json"), "{");

        List<EmoteAnimation.Loaded> loaded = this.loader.load(tempDir, "26.2", animation -> animation);

        assertEquals(1, loaded.size());
        assertEquals("valid:wave", loaded.getFirst().animation().id().toString());
    }

    @Test
    void createsMissingAnimationDirectory(@TempDir Path tempDir) {
        Path directory = tempDir.resolve("animations");

        assertTrue(this.loader.load(directory, "26.2", animation -> animation).isEmpty());
        assertTrue(Files.isDirectory(directory));
    }

    private void writeAnimation(Path path, String id) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(REFERENCE_PATH)).getAsJsonObject();
        root.addProperty("id", id);
        Files.writeString(path, root.toString());
    }
}
