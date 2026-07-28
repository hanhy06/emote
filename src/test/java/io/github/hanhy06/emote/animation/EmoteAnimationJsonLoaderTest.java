package io.github.hanhy06.emote.animation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EmoteAnimationJsonLoaderTest {
    private static final Path REFERENCE_PATH = Path.of("docs/emote-animation-format.json");
    private static final String MINECRAFT_VERSION = System.getProperty("emote.minecraftVersion");
    private final EmoteAnimationJsonLoader loader = new EmoteAnimationJsonLoader();

    @Test
    void loadsFormatReferenceAndIgnoresUnknownMetadataFields() throws Exception {
        JsonObject root = readReference();
        root.getAsJsonObject("metadata").addProperty("future_metadata", "ignored");

        EmoteAnimation.Loaded loaded = parse(root);

        assertEquals("emote:format_reference", loaded.animation().id().toString());
        assertEquals("Format Reference", loaded.animation().metadata().name());
        assertEquals(5, loaded.animation().nodes().size());
        assertEquals(80, loaded.animation().timeline().durationTicks());
        assertEquals(64, loaded.sha256().length());
    }

    @Test
    void loadsServerSynchronizedLoopWithoutChangingSchemaVersion() throws Exception {
        JsonObject root = readReference();
        root.getAsJsonObject("timeline").addProperty("loop", "server_sync");

        EmoteAnimation.Loaded loaded = parse(root);

        assertEquals(1, root.get("schema_version").getAsInt());
        assertEquals(EmoteAnimation.LoopMode.SERVER_SYNC, loaded.animation().timeline().loop());
    }

    @Test
    void loadsAllRepositoryExamplesWhileIgnoringUnknownMetadata() throws Exception {
        List<Path> examplePaths;
        try (var paths = Files.list(Path.of("docs/example"))) {
            examplePaths = paths.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList();
        }

        assertEquals(5, examplePaths.size());
        for (Path examplePath : examplePaths) {
            EmoteAnimation.Loaded loaded = this.loader.load(examplePath, MINECRAFT_VERSION);
            assertFalse(loaded.animation().nodes().isEmpty(), examplePath.toString());
            assertTrue(loaded.animation().metadata().hidePlayer(), examplePath.toString());
        }
    }

    @Test
    void rejectsMatrixWithWrongSizeAtExactFieldPath() throws Exception {
        JsonObject root = readReference();
        JsonArray matrix = root.getAsJsonObject("nodes")
            .getAsJsonObject("player_head")
            .getAsJsonArray("default_matrix");
        matrix.remove(matrix.size() - 1);

        EmoteAnimationLoadException exception = assertThrows(
            EmoteAnimationLoadException.class,
            () -> parse(root)
        );

        assertEquals("$.nodes.player_head.default_matrix", exception.fieldPath());
    }

    @Test
    void rejectsAnchorAsCommandSource() throws Exception {
        JsonObject root = readReference();
        JsonObject source = root.getAsJsonObject("timeline")
            .getAsJsonObject("events")
            .getAsJsonArray("timeline")
            .get(0).getAsJsonObject()
            .getAsJsonObject("source");
        source.addProperty("type", "node");
        source.addProperty("node", "effect_anchor");

        EmoteAnimationLoadException exception = assertThrows(
            EmoteAnimationLoadException.class,
            () -> parse(root)
        );

        assertEquals("$.timeline.events.timeline[0].source.node", exception.fieldPath());
    }

    @Test
    void rejectsInterpolationThatStartsBeforePreviousNodeTransform() throws Exception {
        JsonObject root = readReference();
        JsonObject transform = root.getAsJsonObject("timeline")
            .getAsJsonArray("keyframes")
            .get(1).getAsJsonObject()
            .getAsJsonObject("node_transforms")
            .getAsJsonObject("player_head");
        transform.addProperty("interpolation_duration_ticks", 2);

        EmoteAnimationLoadException exception = assertThrows(
            EmoteAnimationLoadException.class,
            () -> parse(root)
        );

        assertEquals(
            "$.timeline.keyframes[1].node_transforms.player_head.interpolation_duration_ticks",
            exception.fieldPath()
        );
    }

    @Test
    void rejectsMinecraftVersionMismatch() {
        EmoteAnimationLoadException exception = assertThrows(
            EmoteAnimationLoadException.class,
            () -> this.loader.load(
                REFERENCE_PATH,
                MINECRAFT_VERSION + "-mismatch"
            )
        );

        assertEquals("$.minecraft_version", exception.fieldPath());
    }

    private JsonObject readReference() throws IOException {
        JsonObject root = JsonParser
            .parseString(Files.readString(REFERENCE_PATH))
            .getAsJsonObject();

        root.addProperty("minecraft_version", MINECRAFT_VERSION);
        return root;
    }

    private EmoteAnimation.Loaded parse(JsonObject root) throws EmoteAnimationLoadException {
        return this.loader.parse(
            REFERENCE_PATH,
            root.toString().getBytes(StandardCharsets.UTF_8),
            MINECRAFT_VERSION
        );
    }
}
