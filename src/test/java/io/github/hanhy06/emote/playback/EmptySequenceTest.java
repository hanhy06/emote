package io.github.hanhy06.emote.playback;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.math.Transformation;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.api.PlaybackTimeline;
import io.github.hanhy06.emote.api.sequence.EmoteSequence;
import io.github.hanhy06.emote.content.PreparedAnimation;
import io.github.hanhy06.emote.content.PreparedSequence;
import io.github.hanhy06.emote.content.loader.AnimationJsonParser;
import io.github.hanhy06.emote.content.loader.SequenceJsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class EmptySequenceTest {
    private static HolderLookup.Provider registries;

    @BeforeAll
    static void initializeMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = VanillaRegistries.createWorldLookup();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries).forEach(DataComponentInitializers.PendingComponents::apply);
    }

    @Test
    void hidesPreparedDisplaysAndSeeksBetweenEmptyAndAnimationWithoutRestartingTheSequence(@TempDir Path directory) throws Exception {
        JsonObject json;
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/common-curves.json"))) {
            json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        json.addProperty("id", "test:empty_pose");
        json.add("nodes", JsonParser.parseString("""
            {"n":{"transform":[{"id":"position","op":"translate","value":[0,0,0]}],
                  "attachments":{"skin":{"type":"player_skin","part":"head","region":{"from":0,"to":1}},
                                 "text":{"type":"text_display","text":"probe"}}}}
            """));
        json.add("animation", JsonParser.parseString("""
            {"duration":"2t",
             "tracks":[{"target":{"node":"n","operation":"position"},"channel":"value",
                         "driver":{"type":"expression","value":[{"molang":"10 + q.anim_time_ticks"},0,0]}}],
             "events":{"start":[{"source":{"type":"server"},"origin":{"type":"root"},
                                   "action":{"type":"commands","commands":["say start"]}}],
                       "timeline":[],"loop":[],
                       "stop":[{"source":{"type":"server"},"origin":{"type":"root"},
                                 "action":{"type":"commands","commands":["say stop"]}}]}}
            """));
        var animation = PreparedAnimation.prepare(new AnimationJsonParser().parse(directory.resolve("pose.json"),
            json.toString().getBytes(StandardCharsets.UTF_8)), registries);
        json.addProperty("type", "sequence");
        json.addProperty("id", "test:empty_sequence");
        json.remove("nodes");
        json.remove("animation");
        json.add("steps", JsonParser.parseString("""
            [{"emote":"emote:empty"},{"emote":"test:empty_pose","transition":"4t"},
             {"emote":"emote:empty","repeat":2},{"emote":"test:empty_pose","transition":"4t"}]
            """));
        Path path = directory.resolve("sequence.json");
        Files.writeString(path, json.toString());
        var sequence = PreparedSequence.prepare(new SequenceJsonParser().parse(path).model(), Map.of(animation.id(), animation));
        assertEquals(animation.skinBindings(), sequence.skinBindings());
        assertEquals(animation.displayEntityCount(), sequence.displayEntityCount());
        assertEquals(2, sequence.displayEntityCount());
        assertNull(sequence.durationTicks());

        var playback = new EmotePlayback(sequence);
        var target = new PoseTarget();
        var effects = new ArrayList<String>();
        playback.bindEvents(event -> effects.add(event.event().commands().getFirst()));
        playback.bindLifecycleListener(new EmotePlayback.LifecycleListener() {
            public void onStart(PreparedAnimation child) { effects.add("start"); }
            public void onClose(PlaybackStopReason reason) { effects.add("close:" + reason); }
        });
        var frame = playback.prepareStart(target);
        frame.calculate();
        assertTrue(target.visible);
        frame.apply();
        frame.dispatchEvents();
        assertFalse(target.visible);
        assertTrue(effects.isEmpty());
        assertEquals(PlaybackTimeline.Phase.EMPTY, frame.pose().position().phase());
        assertNull(frame.pose().position().animationId());
        assertNull(frame.pose().position().animationTick());
        assertNull(frame.timeline().durationTicks());
        assertEquals(List.of(PlaybackTimeline.Phase.EMPTY, PlaybackTimeline.Phase.ANIMATION,
            PlaybackTimeline.Phase.EMPTY, PlaybackTimeline.Phase.EMPTY, PlaybackTimeline.Phase.ANIMATION),
            frame.timeline().segments().stream().map(PlaybackTimeline.Segment::phase).toList());

        frame = apply(playback.prepareAdvance(120));
        assertEquals(EmotePlayback.AdvanceResult.CONTINUE, frame.result());
        assertEquals(120, frame.pose().position().phaseTick());
        assertFalse(target.visible);
        assertTrue(effects.isEmpty());

        frame = apply(playback.prepareStep(1, 0, 0));
        assertTrue(target.visible);
        assertEquals(10, target.matrix.m30());
        assertEquals(0, frame.pose().position().animationTick());
        assertEquals(List.of("say start", "start"), effects);
        assertEquals(120, frame.timeline().segments().getFirst().endTick());

        frame = apply(playback.prepareAdvance(2));
        assertEquals(PlaybackTimeline.Phase.EMPTY, frame.pose().position().phase());
        assertEquals(2, frame.pose().position().stepIndex());
        assertFalse(target.visible);
        assertEquals(List.of("say start", "start", "say stop", "close:FINISHED"), effects);
        apply(playback.prepareAdvance(10));
        assertEquals(4, effects.size());
        assertFalse(target.visible);

        frame = apply(playback.prepareStep(2, 1, 0));
        assertEquals(1, frame.pose().position().repeatIndex());
        assertFalse(target.visible);
        frame = apply(playback.prepareStep(3, 0, 1));
        assertEquals(PlaybackTimeline.Phase.ANIMATION, frame.pose().position().phase());
        assertEquals(11, target.matrix.m30());
        assertTrue(target.visible);
        apply(playback.prepareStep(0, 0, 0));
        assertFalse(target.visible);
        assertEquals("close:REPLACED", effects.getLast());
        assertThrows(IllegalArgumentException.class, () -> playback.stepSegment(0, 0, 1));
        int effectCount = effects.size();
        frame = apply(playback.prepareStop(PlaybackStopReason.MANUAL));
        assertEquals(EmotePlayback.AdvanceResult.FINISHED, frame.result());
        assertEquals(effectCount, effects.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void treatsEmptyAsPoseBoundaryWhenSeekingIntoWait(boolean tickMolang, @TempDir Path directory) throws Exception {
        JsonObject json = JsonParser.parseString("""
            {"type":"animation","schema_version":5,"id":"test:seek_pose","metadata":{"name":"Seek pose","description":""},
             "nodes":{"n":{"transform":[{"id":"position","op":"translate","value":[0,0,0]}],
                             "attachments":{"text":{"type":"text_display","text":"probe"}}}},
             "animation":{"duration":"2t",
                          "tracks":[{"target":{"node":"n","operation":"position"},"channel":"value",
                                      "driver":{"type":"expression","value":[{"molang":"10 + q.anim_time_ticks"},0,0]}}]}}
            """).getAsJsonObject();
        if (tickMolang) json.getAsJsonObject("animation").add("molang", JsonParser.parseString("{\"tick\":\"v.frames = v.frames + 1;\"}"));
        var animation = PreparedAnimation.prepare(new AnimationJsonParser().parse(directory.resolve("pose.json"),
            json.toString().getBytes(StandardCharsets.UTF_8)), registries);
        var settings = new EmoteSequence.Settings(0, animation.playerBehavior());
        var sequence = PreparedSequence.prepare(new EmoteSequence(Identifier.parse("test:empty_wait"), animation.metadata(), settings,
            List.of(new EmoteSequence.AnimationStep(animation.model().id(), 1),
                new EmoteSequence.AnimationStep(EmoteSequence.Control.EMPTY.id(), 1),
                new EmoteSequence.WaitStep(5), new EmoteSequence.AnimationStep(animation.model().id(), 1))), Map.of(animation.id(), animation));
        var playback = new EmotePlayback(sequence);
        var target = new PoseTarget();
        apply(playback.prepareStart(target));
        apply(playback.prepareAdvance(2));
        assertEquals(PlaybackTimeline.Phase.EMPTY, playback.position().phase());
        assertFalse(target.visible);
        apply(playback.prepareAdvance(10));
        apply(playback.prepareStep(3, 0, 0));
        assertTrue(target.visible);
        assertEquals(10, target.matrix.m30());
        assertTrue(playback.canSetTick(13));
        var frame = apply(playback.prepareTick(13));
        assertEquals(PlaybackTimeline.Phase.WAIT, frame.pose().position().phase());
        assertFalse(target.visible);
        apply(playback.prepareAdvance());
        assertFalse(target.visible);

        sequence = PreparedSequence.prepare(new EmoteSequence(Identifier.parse("test:pose_wait"), animation.metadata(), settings,
            List.of(new EmoteSequence.AnimationStep(animation.model().id(), 1),
                new EmoteSequence.WaitStep(5), new EmoteSequence.AnimationStep(animation.model().id(), 1))), Map.of(animation.id(), animation));
        var posePlayback = new EmotePlayback(sequence);
        target = new PoseTarget();
        apply(posePlayback.prepareStart(target));
        apply(posePlayback.prepareAdvance(2));
        assertEquals(PlaybackTimeline.Phase.WAIT, posePlayback.position().phase());
        assertTrue(target.visible);
        assertEquals(12, target.matrix.m30());
        apply(posePlayback.prepareStep(2, 0, 0));
        if (tickMolang) {
            assertThrows(IllegalArgumentException.class, () -> posePlayback.canSetTick(3));
        } else {
            assertTrue(posePlayback.canSetTick(3));
            frame = apply(posePlayback.prepareTick(3));
            assertEquals(PlaybackTimeline.Phase.WAIT, frame.pose().position().phase());
            assertTrue(target.visible);
            assertEquals(12, target.matrix.m30());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesSequenceLengthsThroughSamplingEmptyAndAnimationSeek(boolean dynamic, @TempDir Path directory) throws Exception {
        JsonObject json = JsonParser.parseString("""
            {"type":"animation","schema_version":5,"id":"test:timed_pose","metadata":{"name":"Timed pose","description":""},
             "nodes":{"n":{"attachments":{"text":{"type":"text_display","text":"probe"}}}},
             "animation":{"duration":"4t","tracks":[],
                          "playback":{"mode":"loop","start_delay":"2t","loop_delay":"3t"}}}
            """).getAsJsonObject();
        if (dynamic) {
            json.getAsJsonObject("animation").add("clock", JsonParser.parseString("{\"type\":\"molang\",\"expression\":\"q.anim_time + q.delta_time\"}"));
            json.getAsJsonObject("animation").add("playback", JsonParser.parseString("{\"mode\":\"loop\",\"start_delay\":{\"molang\":\"0.1\"},\"loop_delay\":{\"molang\":\"0.15\"}}"));
        }
        var animation = PreparedAnimation.prepare(new AnimationJsonParser().parse(directory.resolve("pose.json"),
            json.toString().getBytes(StandardCharsets.UTF_8)), registries);
        var sequence = PreparedSequence.prepare(new EmoteSequence(Identifier.parse("test:sequence_lengths"), animation.metadata(),
            new EmoteSequence.Settings(0, animation.playerBehavior()), List.of(
                new EmoteSequence.AnimationStep(animation.model().id(), 2, 1), new EmoteSequence.WaitStep(4),
                new EmoteSequence.AnimationStep(EmoteSequence.Control.EMPTY.id(), 1),
                new EmoteSequence.AnimationStep(animation.model().id(), 1))), Map.of(animation.id(), animation));
        var playback = new EmotePlayback(sequence);
        var frame = apply(playback.prepareStart(new PoseTarget()));
        assertEquals(2, frame.timeline().segments().getFirst().endTick());
        assertEquals(dynamic ? null : Integer.valueOf(6), frame.timeline().segments().get(1).endTick());
        assertEquals(dynamic ? null : Integer.valueOf(9), frame.timeline().segments().get(2).endTick());
        for (int tick = 0; tick < 6; tick++) frame = apply(playback.prepareAdvance());
        assertEquals(PlaybackTimeline.Phase.LOOP_DELAY, frame.pose().position().phase());
        assertEquals(6, frame.timeline().segments().get(1).endTick());
        assertEquals(9, frame.timeline().segments().get(2).endTick());
        for (int tick = 6; tick < 20; tick++) frame = apply(playback.prepareAdvance());
        assertEquals(PlaybackTimeline.Phase.EMPTY, frame.pose().position().phase());
        assertEquals(List.of(2, 6, 9, 10, 12, 16, 20), frame.timeline().segments().subList(0, 7).stream().map(PlaybackTimeline.Segment::endTick).toList());
        assertNull(frame.timeline().segments().get(7).endTick());
        apply(playback.prepareAdvance(7));
        frame = apply(playback.prepareStep(3, 0, 0));
        assertEquals(27, frame.timeline().segments().get(7).endTick());
        frame = apply(playback.prepareAnimationTick(3));
        assertEquals(dynamic ? null : Integer.valueOf(28), frame.timeline().segments().getLast().endTick());
        frame = apply(playback.prepareAdvance());
        assertEquals(EmotePlayback.AdvanceResult.FINISHED, frame.result());
        assertEquals(28, frame.timeline().segments().getLast().endTick());
    }

    private static EmotePlayback.Frame apply(EmotePlayback.Frame frame) {
        frame.calculate();
        frame.apply();
        frame.dispatchEvents();
        return frame;
    }

    private static final class PoseTarget implements EmotePlayback.PoseTarget {
        Matrix4f matrix = new Matrix4f();
        boolean visible = true;

        public Matrix4fc rootMatrix() { return new Matrix4f(); }
        public Transformation createTransformation(String nodeId, Matrix4fc matrix) { return new Transformation(new Matrix4f(matrix)); }
        public void applyTransform(String nodeId, Transformation transformation, int interpolationDurationTicks) { this.matrix = transformation.getMatrixCopy(); }
        public void setVisible(String nodeId, boolean visible) { this.visible = visible; }
        public void setVisible(String nodeId, String attachmentId, boolean visible) {}
        public void applyNbt(String nodeId, String attachmentId, CompoundTag nbt, java.util.Set<String> removed) {}
        public void resetAll() { this.matrix = new Matrix4f(); this.visible = true; }
    }
}
