package io.github.hanhy06.emote.animation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;
import io.github.hanhy06.emote.api.ParticipantRole;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnimationJsonLoaderTest {
    private static final Path REFERENCE_PATH = Path.of("docs/emote-animation-format.json");
    private final AnimationJsonLoader loader = new AnimationJsonLoader();

    @Test
    void loadsFormatReferenceAndPreservesUnknownMetadataFields() throws Exception {
        JsonObject root = readReference();
        root.getAsJsonObject("metadata").addProperty("future_metadata", "ignored");

        EmoteAnimation.Loaded loaded = parse(root);

        assertEquals("emote:format_reference", loaded.animation().id().toString());
        assertEquals("Format Reference", loaded.animation().metadata().name());
        assertEquals("ignored", loaded.animation().metadata().additional().get("future_metadata").getAsString());
        assertTrue(loaded.animation().settings().standalone());
        assertTrue(loaded.animation().settings().player().hidden());
        assertEquals(0.1D, loaded.animation().settings().player().stopConditions().movementDistance());
        assertTrue(loaded.animation().settings().player().stopConditions().jump());
        assertEquals(5, loaded.animation().nodes().size());
        assertEquals(80, loaded.animation().timeline().durationTicks());
        assertEquals(64, loaded.sha256().length());
    }

    @Test
    void readsExplicitStandaloneSetting() throws Exception {
        JsonObject root = readReference();
        assertTrue(parse(root).animation().settings().standalone());

        root.getAsJsonObject("settings").addProperty("standalone", false);
        assertFalse(parse(root).animation().settings().standalone());
    }

    @Test
    void loadsServerSynchronizedLoopWithoutChangingSchemaVersion() throws Exception {
        JsonObject root = readReference();
        root.getAsJsonObject("settings").getAsJsonObject("playback").addProperty("mode", "server_sync");

        EmoteAnimation.Loaded loaded = parse(root);

        assertEquals(3, root.get("schema_version").getAsInt());
        assertEquals(EmoteAnimation.LoopMode.SERVER_SYNC, loaded.animation().settings().playback().mode());
    }

    @Test
    void loadsNodeSpaceAndSkinParticipant() throws Exception {
        EmoteAnimation animation = parse(readReference()).animation();
        EmoteAnimation.ItemNode head = (EmoteAnimation.ItemNode) animation.nodes().get("player_head");

        assertEquals(EmoteAnimation.NodeSpace.INITIATOR, head.space());
        assertEquals(ParticipantRole.INITIATOR, head.skin().participant());
        assertEquals(EmoteAnimation.NodeSpace.SCENE, animation.nodes().get("effect_anchor").space());
    }

    @Test
    void loadsSchemaTwoStyleNodesAfterOnlyChangingSchemaVersion() throws Exception {
        JsonObject root = readReference();
        JsonObject playerHead = root.getAsJsonObject("nodes").getAsJsonObject("player_head");
        playerHead.remove("space");
        playerHead.getAsJsonObject("skin").remove("participant");
        root.getAsJsonObject("nodes").getAsJsonObject("effect_anchor").remove("space");

        EmoteAnimation animation = parse(root).animation();

        assertEquals(EmoteAnimation.NodeSpace.INITIATOR, animation.nodes().get("player_head").space());
        assertEquals(ParticipantRole.INITIATOR, ((EmoteAnimation.ItemNode) animation.nodes().get("player_head")).skin().participant());
        assertEquals(EmoteAnimation.NodeSpace.SCENE, animation.nodes().get("effect_anchor").space());
    }

    @Test
    void rejectsSkinParticipantThatDoesNotMatchNodeSpace() throws Exception {
        JsonObject root = readReference();
        root.getAsJsonObject("nodes").getAsJsonObject("player_head")
            .getAsJsonObject("skin").addProperty("participant", "partner");

        EmoteAnimationLoadException exception = assertThrows(
            EmoteAnimationLoadException.class,
            () -> parse(root)
        );

        assertEquals("$.nodes.player_head.skin.participant", exception.fieldPath());
    }

    @Test
    void parsesMinecraftTimeStringsAtLoadBoundary() throws Exception {
        JsonObject root = readReference();
        root.getAsJsonObject("settings").addProperty("cooldown", "1.5s");
        root.getAsJsonObject("timeline").addProperty("duration", "4s");

        EmoteAnimation.Loaded loaded = parse(root);

        assertEquals(30, loaded.animation().settings().cooldownTicks());
        assertEquals(80, loaded.animation().timeline().durationTicks());
    }

    @Test
    void loadsAllRepositoryExamplesWhileIgnoringUnknownMetadata() throws Exception {
        List<Path> examplePaths;
        try (var paths = Files.list(Path.of("docs/example"))) {
            examplePaths = paths.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList();
        }

        assertEquals(6, examplePaths.size());
        for (Path examplePath : examplePaths) {
            EmoteAnimation.Loaded loaded = this.loader.load(examplePath);
            assertFalse(loaded.animation().nodes().isEmpty(), examplePath.toString());
            assertTrue(loaded.animation().settings().player().hidden(), examplePath.toString());
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
    void rejectsNegativeMovementStopDistanceAtExactFieldPath() throws Exception {
        JsonObject root = readReference();
        root.getAsJsonObject("settings").getAsJsonObject("player")
            .getAsJsonObject("stop_conditions")
            .addProperty("movement_distance", -0.1D);

        EmoteAnimationLoadException exception = assertThrows(
            EmoteAnimationLoadException.class,
            () -> parse(root)
        );

        assertEquals("$.settings.player.stop_conditions.movement_distance", exception.fieldPath());
    }

    @Test
    void stabilizesDisplayMatricesWithoutChangingAnchorMatrices() throws Exception {
        JsonObject root = readReference();
        JsonArray shearedMatrix = JsonParser.parseString("""
            [1,0.25,0,4,0,1,0.2,5,0,0,0.5,6,0,0,0,1]
            """).getAsJsonArray();
        root.getAsJsonObject("nodes").getAsJsonObject("player_head")
            .add("default_matrix", shearedMatrix.deepCopy());
        root.getAsJsonObject("nodes").getAsJsonObject("effect_anchor")
            .add("default_matrix", shearedMatrix.deepCopy());
        root.getAsJsonObject("timeline").getAsJsonArray("keyframes")
            .get(0).getAsJsonObject()
            .getAsJsonObject("node_transforms")
            .getAsJsonObject("player_head")
            .add("matrix", shearedMatrix.deepCopy());

        EmoteAnimation animation = parse(root).animation();
        EmoteAnimation.Matrix displayDefault = animation.nodes().get("player_head").defaultMatrix();
        EmoteAnimation.Matrix anchorDefault = animation.nodes().get("effect_anchor").defaultMatrix();
        EmoteAnimation.Matrix displayKeyframe = animation.timeline().keyframes().getFirst()
            .nodeTransforms().get("player_head").matrix();

        assertEquals(0.0D, normalizedColumnDot(displayDefault, 0, 1), 1.0E-8D);
        assertEquals(0.0D, normalizedColumnDot(displayKeyframe, 0, 1), 1.0E-8D);
        assertNotEquals(0.0D, normalizedColumnDot(anchorDefault, 0, 1), 1.0E-8D);
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
        transform.addProperty("interpolation_duration", "2t");

        EmoteAnimationLoadException exception = assertThrows(
            EmoteAnimationLoadException.class,
            () -> parse(root)
        );

        assertEquals(
            "$.timeline.keyframes[1].node_transforms.player_head.interpolation_duration",
            exception.fieldPath()
        );
    }

    @Test
    void rejectsNumericTimeValues() throws Exception {
        JsonObject root = readReference();
        root.getAsJsonObject("timeline").addProperty("duration", 80);

        EmoteAnimationLoadException exception = assertThrows(
            EmoteAnimationLoadException.class,
            () -> parse(root)
        );

        assertEquals("$.timeline.duration", exception.fieldPath());
    }

    @Test
    void rejectsOversizedAnimationBeforeParsingJson() {
        byte[] bytes = new byte[AnimationJsonLoader.MAX_JSON_BYTES + 1];

        EmoteAnimationLoadException exception = assertThrows(
            EmoteAnimationLoadException.class,
            () -> this.loader.parse(REFERENCE_PATH, bytes)
        );

        assertEquals("$", exception.fieldPath());
    }

    private JsonObject readReference() throws IOException {
        JsonObject root = JsonParser
            .parseString(Files.readString(REFERENCE_PATH))
            .getAsJsonObject();

        return root;
    }

    private EmoteAnimation.Loaded parse(JsonObject root) throws EmoteAnimationLoadException {
        return this.loader.parse(
            REFERENCE_PATH,
            root.toString().getBytes(StandardCharsets.UTF_8)
        );
    }

    private double normalizedColumnDot(EmoteAnimation.Matrix matrix, int first, int second) {
        double dot = 0.0D;
        double firstLengthSquared = 0.0D;
        double secondLengthSquared = 0.0D;
        for (int row = 0; row < 3; row++) {
            double firstValue = matrix.value(row * 4 + first);
            double secondValue = matrix.value(row * 4 + second);
            dot += firstValue * secondValue;
            firstLengthSquared += firstValue * firstValue;
            secondLengthSquared += secondValue * secondValue;
        }
        return dot / Math.sqrt(firstLengthSquared * secondLengthSquared);
    }
}
