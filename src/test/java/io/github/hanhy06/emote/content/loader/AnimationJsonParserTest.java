package io.github.hanhy06.emote.content.loader;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.hanhy06.emote.api.ParticipantRole;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;
import io.github.hanhy06.emote.content.LoadedAnimation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnimationJsonParserTest {
    private static final Path REFERENCE_PATH = Path.of("docs/reference/animation.json");
    private final AnimationJsonParser parser = new AnimationJsonParser();

    @Test
    void loadsFormatReferenceAndPreservesUnknownMetadataFields() throws Exception {
        JsonObject root = readReference();
        root.getAsJsonObject("metadata").addProperty("future_metadata", "ignored");

        LoadedAnimation loaded = parse(root);

        assertEquals("emote:format_reference", loaded.animation().id().toString());
        assertEquals("Format Reference", loaded.animation().metadata().name());
        assertEquals("ignored", loaded.animation().metadata().additional().get("future_metadata").getAsString());
        assertTrue(loaded.animation().settings().standalone());
        assertTrue(loaded.animation().settings().player().hidden());
        assertEquals(0.1D, loaded.animation().settings().player().stopConditions().movementDistance());
        assertTrue(loaded.animation().settings().player().stopConditions().jump());
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
    void loadsServerSynchronizedLoop() throws Exception {
        JsonObject root = readReference();
        root.getAsJsonObject("settings").getAsJsonObject("playback").addProperty("mode", "server_sync");

        LoadedAnimation loaded = parse(root);

        assertEquals(EmoteAnimation.LoopMode.SERVER_SYNC, loaded.animation().settings().playback().mode());
    }

    @Test
    void loadsHoldPlaybackMode() throws Exception {
        JsonObject root = readReference();
        root.getAsJsonObject("settings").getAsJsonObject("playback").addProperty("mode", "hold");

        assertEquals(EmoteAnimation.LoopMode.HOLD, parse(root).animation().settings().playback().mode());
    }

    @Test
    void loadsNodeSpaceAndSkinParticipant() throws Exception {
        EmoteAnimation animation = parse(readReference()).animation();
        EmoteAnimation.ItemNode head = (EmoteAnimation.ItemNode) animation.nodes().get("player_head");
        EmoteAnimation.ItemNode partnerHead = (EmoteAnimation.ItemNode) animation.nodes().get("partner_head");

        assertEquals(EmoteAnimation.NodeSpace.INITIATOR, head.space());
        assertEquals(ParticipantRole.INITIATOR, head.skin().participant());
        assertEquals(EmoteAnimation.NodeSpace.PARTNER, partnerHead.space());
        assertEquals(ParticipantRole.PARTNER, partnerHead.skin().participant());
        assertEquals(EmoteAnimation.NodeSpace.SCENE, animation.nodes().get("effect_anchor").space());
    }

    @Test
    void rejectsRootNodeWithoutSpace() throws Exception {
        JsonObject root = readReference();
        JsonObject playerHead = root.getAsJsonObject("nodes").getAsJsonObject("player_head");
        playerHead.remove("space");

        EmoteAnimationLoadException exception = assertThrows(
            EmoteAnimationLoadException.class,
            () -> parse(root)
        );

        assertEquals("$.nodes.player_head.space", exception.fieldPath());
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

        LoadedAnimation loaded = parse(root);

        assertEquals(30, loaded.animation().settings().cooldownTicks());
        assertEquals(80, loaded.animation().timeline().durationTicks());
    }

    @Test
    void loadsAllRepositoryExamplesWhileIgnoringUnknownMetadata() throws Exception {
        List<Path> examplePaths;
        try (var paths = Files.walk(Path.of("docs/sample"))) {
            examplePaths = paths.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".json"))
                .filter(path -> !path.getFileName().toString().endsWith(".sequence.json"))
                .sorted()
                .toList();
        }

        assertFalse(examplePaths.isEmpty());
        for (Path examplePath : examplePaths) {
            LoadedAnimation loaded = this.parser.parse(examplePath);
            assertFalse(loaded.animation().nodes().isEmpty(), examplePath.toString());
            assertTrue(loaded.animation().settings().player().hidden(), examplePath.toString());
        }
    }

    @Test
    void rejectsTransformVectorWithWrongSizeAtExactFieldPath() throws Exception {
        JsonObject root = readReference();
        JsonArray position = root.getAsJsonObject("nodes")
            .getAsJsonObject("player_head")
            .getAsJsonObject("transform")
            .getAsJsonArray("position");
        position.remove(position.size() - 1);

        EmoteAnimationLoadException exception = assertThrows(
            EmoteAnimationLoadException.class,
            () -> parse(root)
        );

        assertEquals("$.nodes.player_head.transform.position", exception.fieldPath());
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
    void loadsDisplayAndAnchorLocalTransforms() throws Exception {
        JsonObject root = readReference();
        JsonArray position = new JsonArray();
        position.add(4.0D);
        position.add(5.0D);
        position.add(6.0D);
        root.getAsJsonObject("nodes").getAsJsonObject("player_head").getAsJsonObject("transform")
            .add("position", position.deepCopy());
        root.getAsJsonObject("nodes").getAsJsonObject("effect_anchor").getAsJsonObject("transform")
            .add("position", position.deepCopy());

        EmoteAnimation animation = parse(root).animation();
        EmoteAnimation.LocalTransform displayDefault = animation.nodes().get("player_head").transform();
        EmoteAnimation.LocalTransform anchorDefault = animation.nodes().get("effect_anchor").transform();

        assertEquals(new EmoteAnimation.Vec3(4.0D, 5.0D, 6.0D), displayDefault.position());
        assertEquals(displayDefault, anchorDefault);
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
    void rejectsInterpolationOnLastTrackKeyframe() throws Exception {
        JsonObject root = readReference();
        JsonArray track = root.getAsJsonObject("timeline")
            .getAsJsonObject("tracks")
            .getAsJsonObject("player_head")
            .getAsJsonArray("position");
        track.get(track.size() - 1).getAsJsonObject().addProperty("interpolation", "linear");

        EmoteAnimationLoadException exception = assertThrows(
            EmoteAnimationLoadException.class,
            () -> parse(root)
        );

        assertEquals(
            "$.timeline.tracks.player_head.position[1]",
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
        byte[] bytes = new byte[EmoteJsonDocument.MAX_JSON_BYTES + 1];

        EmoteAnimationLoadException exception = assertThrows(
            EmoteAnimationLoadException.class,
            () -> this.parser.parse(REFERENCE_PATH, bytes)
        );

        assertEquals("$", exception.fieldPath());
    }

    private JsonObject readReference() throws IOException {
        JsonObject root = JsonParser
            .parseString(Files.readString(REFERENCE_PATH))
            .getAsJsonObject();

        return root;
    }

    private LoadedAnimation parse(JsonObject root) throws EmoteAnimationLoadException {
        return this.parser.parse(
            REFERENCE_PATH,
            root.toString().getBytes(StandardCharsets.UTF_8)
        );
    }

}
