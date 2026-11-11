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
        assertEquals(PlaybackTimeline.Phase.EMPTY, frame.pose().cursor().phase());
        assertNull(frame.pose().cursor().animationId());
        assertNull(frame.pose().cursor().animationTick());
        assertNull(frame.timeline().durationTicks());
        assertEquals(List.of(PlaybackTimeline.Phase.EMPTY, PlaybackTimeline.Phase.ANIMATION,
            PlaybackTimeline.Phase.EMPTY, PlaybackTimeline.Phase.EMPTY, PlaybackTimeline.Phase.ANIMATION),
            frame.timeline().segments().stream().map(PlaybackTimeline.Segment::phase).toList());

        frame = apply(playback.prepareAdvance(120));
        assertEquals(EmotePlayback.AdvanceResult.CONTINUE, frame.result());
        assertEquals(120, frame.pose().cursor().segmentTick());
        assertFalse(target.visible);
        assertTrue(effects.isEmpty());

        frame = apply(playback.prepareSeekStep(1, 0, 0));
        assertTrue(target.visible);
        assertEquals(10, target.matrix.m30());
        assertEquals(0, frame.pose().cursor().animationTick());
        assertEquals(List.of("say start", "start"), effects);
        assertEquals(120, frame.timeline().segments().getFirst().endTick());

        frame = apply(playback.prepareAdvance(2));
        assertEquals(PlaybackTimeline.Phase.EMPTY, frame.pose().cursor().phase());
        assertEquals(2, frame.pose().cursor().stepIndex());
        assertFalse(target.visible);
        assertEquals(List.of("say start", "start", "say stop", "close:FINISHED"), effects);
        apply(playback.prepareAdvance(10));
        assertEquals(4, effects.size());
        assertFalse(target.visible);

        frame = apply(playback.prepareSeekStep(2, 1, 0));
        assertEquals(1, frame.pose().cursor().repeatIndex());
        assertFalse(target.visible);
        frame = apply(playback.prepareSeekStep(3, 0, 1));
        assertEquals(PlaybackTimeline.Phase.ANIMATION, frame.pose().cursor().phase());
        assertEquals(11, target.matrix.m30());
        assertTrue(target.visible);
        apply(playback.prepareSeekStep(0, 0, 0));
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
        assertEquals(PlaybackTimeline.Phase.EMPTY, playback.cursor().phase());
        assertFalse(target.visible);
        apply(playback.prepareAdvance(10));
        apply(playback.prepareSeekStep(3, 0, 0));
        assertTrue(target.visible);
        assertEquals(10, target.matrix.m30());
        assertTrue(playback.canSeekTimeline(13));
        var frame = apply(playback.prepareSeekTimeline(13));
        assertEquals(PlaybackTimeline.Phase.WAIT, frame.pose().cursor().phase());
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
        assertEquals(PlaybackTimeline.Phase.WAIT, posePlayback.cursor().phase());
        assertTrue(target.visible);
        assertEquals(12, target.matrix.m30());
        apply(posePlayback.prepareSeekStep(2, 0, 0));
        if (tickMolang) {
            assertThrows(IllegalArgumentException.class, () -> posePlayback.canSeekTimeline(3));
        } else {
            assertTrue(posePlayback.canSeekTimeline(3));
            frame = apply(posePlayback.prepareSeekTimeline(3));
            assertEquals(PlaybackTimeline.Phase.WAIT, frame.pose().cursor().phase());
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
        assertEquals(PlaybackTimeline.Phase.LOOP_DELAY, frame.pose().cursor().phase());
        assertEquals(6, frame.timeline().segments().get(1).endTick());
        assertEquals(9, frame.timeline().segments().get(2).endTick());
        for (int tick = 6; tick < 20; tick++) frame = apply(playback.prepareAdvance());
        assertEquals(PlaybackTimeline.Phase.EMPTY, frame.pose().cursor().phase());
        assertEquals(List.of(2, 6, 9, 10, 12, 16, 20), frame.timeline().segments().subList(0, 7).stream().map(PlaybackTimeline.Segment::endTick).toList());
        assertNull(frame.timeline().segments().get(7).endTick());
        apply(playback.prepareAdvance(7));
        frame = apply(playback.prepareSeekStep(3, 0, 0));
        assertEquals(27, frame.timeline().segments().get(7).endTick());
        frame = apply(playback.prepareSeekAnimation(3));
        assertEquals(dynamic ? null : Integer.valueOf(28), frame.timeline().segments().getLast().endTick());
        frame = apply(playback.prepareAdvance());
        assertEquals(EmotePlayback.AdvanceResult.FINISHED, frame.result());
        assertEquals(28, frame.timeline().segments().getLast().endTick());
    }

    @Test
    void keepsCallbackPoseAndTimelineAtTheirEventWhenSeekingAcrossSequenceSteps(@TempDir Path directory) throws Exception {
        var json = """
            {"type":"animation","schema_version":5,"id":"test:callback_pose","metadata":{"name":"Callback pose","description":""},
             "nodes":{"n":{"transform":[{"id":"position","op":"translate","value":[0,0,0]}],
                             "attachments":{"text":{"type":"text_display","text":"probe"}}}},
             "animation":{"duration":"2t","tracks":[{"target":{"node":"n","operation":"position"},"channel":"value",
                 "driver":{"type":"expression","value":[{"molang":"10 + q.anim_time_ticks"},0,0]}}]}}
            """;
        var animation = PreparedAnimation.prepare(new AnimationJsonParser().parse(directory.resolve("pose.json"),
            json.getBytes(StandardCharsets.UTF_8)), registries);
        var sequence = PreparedSequence.prepare(new EmoteSequence(Identifier.parse("test:callback_sequence"), animation.metadata(),
            new EmoteSequence.Settings(0, animation.playerBehavior()), List.of(
                new EmoteSequence.AnimationStep(animation.model().id(), 1),
                new EmoteSequence.AnimationStep(EmoteSequence.Control.EMPTY.id(), 1),
                new EmoteSequence.AnimationStep(animation.model().id(), 1))), Map.of(animation.id(), animation));
        var playback = new EmotePlayback(sequence);
        var starts = new ArrayList<EmotePlayback.PoseView>();
        var closes = new ArrayList<EmotePlayback.PoseView>();
        var reasons = new ArrayList<PlaybackStopReason>();
        playback.bindLifecycleListener(new EmotePlayback.LifecycleListener() {
            public void onStart(PreparedAnimation child) { starts.add(playback.notificationPose()); }
            public void onClose(PlaybackStopReason reason) {
                closes.add(playback.notificationPose());
                reasons.add(reason);
            }
        });
        apply(playback.prepareStart(new PoseTarget()));
        apply(playback.prepareAdvance());
        var frame = apply(playback.prepareAdvance());
        assertEquals(PlaybackTimeline.Phase.EMPTY, frame.pose().cursor().phase());
        var firstClose = closes.getFirst();
        assertEquals(0, firstClose.cursor().stepIndex());
        assertEquals(2, firstClose.cursor().animationTick());
        assertEquals(12, firstClose.nodeMatrix("n").m30());
        assertNull(firstClose.timeline().segments().get(1).endTick());

        for (int tick = 0; tick < 5; tick++) apply(playback.prepareAdvance());
        apply(playback.prepareSeekStep(2, 0, 0));
        var secondStart = starts.getLast();
        assertEquals(2, secondStart.cursor().stepIndex());
        assertEquals(7, secondStart.timelineTick());
        assertEquals(0, secondStart.cursor().animationTick());
        assertEquals(7, secondStart.timeline().segments().get(1).endTick());
        assertEquals(secondStart.cursor().animationId(), secondStart.timeline().segments().get(secondStart.cursor().segmentIndex()).animationId());
        assertEquals(10, secondStart.nodeMatrix("n").m30());
        apply(playback.prepareSeekAnimation(1));
        apply(playback.prepareStop(PlaybackStopReason.MANUAL));
        assertEquals(List.of(PlaybackStopReason.FINISHED, PlaybackStopReason.MANUAL), reasons);
        assertEquals(1, closes.getLast().cursor().animationTick());
        assertEquals(11, closes.getLast().nodeMatrix("n").m30());
        assertEquals(10, secondStart.nodeMatrix("n").m30());
        assertNull(firstClose.timeline().segments().get(1).endTick());
        assertNull(playback.notificationPose());
    }

    @Test
    void sharesMolangVariablesAcrossSequenceExecutionsAndSeek(@TempDir Path directory) throws Exception {
        JsonObject json = JsonParser.parseString("""
            {"type":"animation","schema_version":5,"id":"test:variable_writer","metadata":{"name":"Variables","description":""},
             "nodes":{"n":{"transform":[{"id":"position","op":"translate","value":[0,0,0]}],
                             "attachments":{"text":{"type":"text_display","text":"probe"}}}},
             "animation":{"duration":"2t","molang":{"tick":"v.power = v.power + 1;"},
                          "values":{"local":{"type":"expression","base":[1],"value":[1]}},
                          "tracks":[{"target":{"node":"n","operation":"position"},"channel":"value",
                                      "driver":{"type":"expression","value":[{"molang":"v.power"},
                                          {"molang":"q.value('local', 0)"},{"molang":"q.anim_time_ticks"}]}}]}}
            """).getAsJsonObject();
        var writer = PreparedAnimation.prepare(new AnimationJsonParser().parse(directory.resolve("writer.json"),
            json.toString().getBytes(StandardCharsets.UTF_8)), registries);
        json.addProperty("id", "test:variable_reader");
        json.getAsJsonObject("animation").add("molang", JsonParser.parseString("{\"initialize\":\"variable.power = v.power + q.value('local', 0) + 1 + q.anim_time_ticks;\"}"));
        json.getAsJsonObject("animation").add("values", JsonParser.parseString("{\"local\":{\"type\":\"expression\",\"base\":[9],\"value\":[9]}}"));
        var reader = PreparedAnimation.prepare(new AnimationJsonParser().parse(directory.resolve("reader.json"),
            json.toString().getBytes(StandardCharsets.UTF_8)), registries);
        var sequence = PreparedSequence.prepare(new EmoteSequence(Identifier.parse("test:shared_variables"), writer.metadata(),
            new EmoteSequence.Settings(0, writer.playerBehavior()), List.of(
                new EmoteSequence.AnimationStep(writer.model().id(), 2), new EmoteSequence.WaitStep(1),
                new EmoteSequence.AnimationStep(reader.model().id(), 1, 1),
                new EmoteSequence.AnimationStep(EmoteSequence.Control.EMPTY.id(), 1),
                new EmoteSequence.AnimationStep(writer.model().id(), 1))), Map.of(writer.id(), writer, reader.id(), reader));
        var playback = new EmotePlayback(sequence);
        var target = new PoseTarget();
        apply(playback.prepareStart(target));
        for (int tick = 0; tick < 2; tick++) apply(playback.prepareAdvance());
        assertEquals(2, target.matrix.m30());
        assertEquals(0, target.matrix.m32());
        for (int tick = 0; tick < 2; tick++) apply(playback.prepareAdvance());
        assertEquals(4, target.matrix.m30());
        assertEquals(PlaybackTimeline.Phase.WAIT, playback.cursor().phase());
        apply(playback.prepareAdvance());
        assertEquals(PlaybackTimeline.Phase.TRANSITION, playback.cursor().phase());
        apply(playback.prepareAdvance());
        assertEquals(14, target.matrix.m30());
        assertEquals(9, target.matrix.m31());
        assertEquals(0, target.matrix.m32());
        apply(playback.prepareAdvance());
        apply(playback.prepareSeekAnimation(0));
        assertEquals(14, target.matrix.m30());
        apply(playback.prepareAdvance(2));
        assertEquals(PlaybackTimeline.Phase.EMPTY, playback.cursor().phase());
        apply(playback.prepareSeekStep(4, 0, 0));
        assertEquals(14, target.matrix.m30());
        assertEquals(1, target.matrix.m31());
        apply(playback.prepareAdvance());
        assertEquals(15, target.matrix.m30());
        apply(playback.prepareSeekStep(0, 0, 0));
        assertEquals(15, target.matrix.m30());
        apply(playback.prepareAdvance());
        assertEquals(16, target.matrix.m30());
        apply(playback.prepareSeekStep(2, 0, 0));
        assertEquals(26, target.matrix.m30());
        assertEquals(9, target.matrix.m31());
        var freshTarget = new PoseTarget();
        apply(new EmotePlayback(sequence).prepareStart(freshTarget));
        assertEquals(0, freshTarget.matrix.m30());
        apply(new EmotePlayback(writer).prepareStart(freshTarget));
        assertEquals(0, freshTarget.matrix.m30());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void keepsTimelineAndClipClocksConsistentAcrossAnimationAndSequenceSeek(boolean dynamic, @TempDir Path directory) throws Exception {
        JsonObject json = JsonParser.parseString("""
            {"type":"animation","schema_version":5,"id":"test:timeline_clock","metadata":{"name":"Clock","description":""},
             "nodes":{"n":{"transform":[{"id":"position","op":"translate","value":[0,0,0]}],
                             "attachments":{"text":{"type":"text_display","text":"clock"}}}},
             "animation":{"duration":"8t","playback":{"start_delay":"2t"},
                          "tracks":[{"target":{"node":"n","operation":"position"},"channel":"value",
                                      "driver":{"type":"expression","value":[{"molang":"q.anim_time_ticks"},{"molang":"q.life_time * 20"},0]}}],
                          "events":{"timeline":[{"time":"4t","source":{"type":"server"},"origin":{"type":"root"},
                                                   "action":{"type":"commands","commands":["say clock"]}}]}}}
            """).getAsJsonObject();
        if (dynamic) json.getAsJsonObject("animation").add("clock", JsonParser.parseString("{\"type\":\"molang\",\"expression\":\"q.anim_time + q.delta_time * 2\"}"));
        var animation = PreparedAnimation.prepare(new AnimationJsonParser().parse(directory.resolve("clock.json"),
            json.toString().getBytes(StandardCharsets.UTF_8)), registries);
        var sequence = PreparedSequence.prepare(new EmoteSequence(Identifier.parse("test:timeline_sequence"), animation.metadata(),
            new EmoteSequence.Settings(0, animation.playerBehavior()), List.of(new EmoteSequence.AnimationStep(animation.model().id(), 1))),
            Map.of(animation.id(), animation));
        assertEquals(dynamic ? null : Integer.valueOf(10), animation.info().durationTicks());
        for (var playback : List.of(new EmotePlayback(animation), new EmotePlayback(sequence))) {
            var target = new PoseTarget();
            var events = new ArrayList<EmotePlayback.EventOccurrence>();
            playback.bindEvents(events::add);
            var frame = apply(playback.prepareStart(target));
            assertEquals(dynamic ? null : Integer.valueOf(10), frame.timeline().durationTicks());
            int elapsedTicks = dynamic ? 4 : 6;
            for (int tick = 0; tick < elapsedTicks; tick++) frame = apply(playback.prepareAdvance());
            assertEquals(elapsedTicks, frame.pose().timelineTick());
            assertEquals(elapsedTicks - 2, frame.pose().cursor().segmentTick());
            assertEquals(4, frame.pose().cursor().animationTick());
            assertEquals(4, target.matrix.m30(), 1e-5);
            assertEquals(elapsedTicks, target.matrix.m31(), 1e-5);
            assertEquals(1, events.size());
            var eventPose = events.getFirst().pose();
            assertEquals(elapsedTicks, eventPose.timelineTick());
            assertEquals(4, eventPose.cursor().animationTick());
            if (dynamic) {
                assertThrows(IllegalArgumentException.class, () -> playback.prepareSeekTimeline(3));
            } else {
                frame = apply(playback.prepareSeekTimeline(3));
                assertEquals(3, frame.pose().timelineTick());
                assertEquals(1, frame.pose().cursor().segmentTick());
                assertEquals(1, target.matrix.m30(), 1e-5);
                assertEquals(elapsedTicks, target.matrix.m31(), 1e-5);
            }
            int timelineTick = dynamic ? 4 : 3;
            frame = apply(playback.prepareSeekAnimation(6));
            assertEquals(timelineTick, frame.pose().timelineTick());
            assertEquals(timelineTick - 2, frame.pose().cursor().segmentTick());
            assertEquals(6, frame.pose().cursor().animationTick());
            assertEquals(6, target.matrix.m30(), 1e-5);
            assertEquals(elapsedTicks, target.matrix.m31(), 1e-5);
            assertEquals(1, events.size());
            for (int tick = 0; tick < (dynamic ? 1 : 2); tick++) frame = apply(playback.prepareAdvance());
            assertEquals(EmotePlayback.AdvanceResult.FINISHED, frame.result());
            assertEquals(5, frame.pose().timelineTick());
            assertEquals(5, frame.timeline().durationTicks());
            assertEquals(8, animation.model().clip().durationTicks());
            assertEquals(8, target.matrix.m30(), 1e-5);
            assertEquals(elapsedTicks, eventPose.timelineTick());
            assertEquals(4, eventPose.cursor().animationTick());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"once", "hold", "loop", "server_sync"})
    void resolvesTimelineAtDelayHoldAndLoopBoundaries(String mode, @TempDir Path directory) throws Exception {
        String json = """
            {"type":"animation","schema_version":5,"id":"test:timeline_mode","metadata":{"name":"Mode","description":""},
             "nodes":{"n":{"transform":[{"id":"position","op":"translate","value":[0,0,0]}],
                             "attachments":{"text":{"type":"text_display","text":"mode"}}}},
             "animation":{"duration":"6t","playback":{"mode":"%s","start_delay":"2t","loop_delay":"2t","loop_start":"%st"},
                          "tracks":[{"target":{"node":"n","operation":"position"},"channel":"value",
                                      "driver":{"type":"expression","value":[{"molang":"q.anim_time_ticks"},{"molang":"q.life_time * 20"},0]}}],
                          "events":{"timeline":[{"time":"6t","source":{"type":"server"},"origin":{"type":"root"},
                                                   "action":{"type":"commands","commands":["say boundary"]}}],
                                    "stop":[{"source":{"type":"server"},"origin":{"type":"root"},
                                             "action":{"type":"commands","commands":["say stopped"]}}]}}}
            """.formatted(mode, mode.equals("loop") ? 2 : 0);
        var animation = PreparedAnimation.prepare(new AnimationJsonParser().parse(directory.resolve("mode.json"),
            json.getBytes(StandardCharsets.UTF_8)), registries);
        var playback = new EmotePlayback(animation);
        var target = new PoseTarget();
        var events = new ArrayList<EmotePlayback.EventOccurrence>();
        playback.bindEvents(events::add);
        var frame = apply(mode.equals("server_sync") ? playback.prepareStartSynchronized(target, 0) : playback.prepareStart(target));
        assertEquals(mode.equals("once") ? Integer.valueOf(8) : null, animation.info().durationTicks());
        assertEquals(animation.info().durationTicks(), frame.timeline().durationTicks());
        for (int tick = 0; tick < 8; tick++) frame = apply(playback.prepareAdvance());
        assertEquals(8, frame.pose().timelineTick());
        assertEquals(6, target.matrix.m30(), 1e-5);
        assertEquals(1, events.size());
        assertEquals(8, events.getFirst().pose().timelineTick());
        if (mode.equals("once")) {
            assertEquals(EmotePlayback.AdvanceResult.FINISHED, frame.result());
            assertThrows(IllegalArgumentException.class, () -> playback.canSeekTimeline(9));
        } else if (mode.equals("hold")) {
            assertEquals(PlaybackTimeline.Phase.HOLD, frame.pose().cursor().phase());
            assertEquals(0, frame.pose().cursor().segmentTick());
            frame = apply(playback.prepareAdvance(3));
            assertEquals(11, frame.pose().timelineTick());
            assertEquals(3, frame.pose().cursor().segmentTick());
            frame = apply(playback.prepareSeekTimeline(10));
            assertEquals(PlaybackTimeline.Phase.HOLD, frame.pose().cursor().phase());
            assertEquals(2, frame.pose().cursor().segmentTick());
            assertEquals(6, target.matrix.m30(), 1e-5);
        } else {
            assertEquals(PlaybackTimeline.Phase.LOOP_DELAY, frame.pose().cursor().phase());
            assertEquals(0, frame.pose().cursor().segmentTick());
            frame = apply(playback.prepareAdvance());
            assertEquals(9, frame.pose().timelineTick());
            assertEquals(1, frame.pose().cursor().segmentTick());
            frame = apply(playback.prepareAdvance());
            int loopStartTick = mode.equals("loop") ? 4 : 2;
            assertEquals(loopStartTick, frame.pose().timelineTick());
            assertEquals(loopStartTick, frame.timeline().loopStartTick());
            assertEquals(0, frame.pose().cursor().segmentTick());
            frame = apply(playback.prepareAdvance());
            assertEquals(loopStartTick + 1, frame.pose().timelineTick());
            assertEquals(loopStartTick - 1, target.matrix.m30(), 1e-5);
            assertEquals(11, target.matrix.m31(), 1e-5);
            if (mode.equals("loop")) {
                frame = apply(playback.prepareSeekTimeline(9));
                assertEquals(PlaybackTimeline.Phase.LOOP_DELAY, frame.pose().cursor().phase());
                assertEquals(10, frame.timeline().segments().getLast().endTick());
                frame = apply(playback.prepareAdvance());
                assertEquals(loopStartTick, frame.pose().timelineTick());
                frame = apply(playback.prepareSeekTimeline(1));
                assertEquals(PlaybackTimeline.Phase.START_DELAY, frame.pose().cursor().phase());
                assertEquals(1, frame.pose().cursor().segmentTick());
                frame = apply(playback.prepareAdvance());
                assertEquals(2, frame.pose().timelineTick());
                assertEquals(0, frame.pose().cursor().animationTick());
            } else assertFalse(playback.canSeekTimeline(1));
        }
        assertEquals(8, events.getFirst().pose().timelineTick());
        var lastPose = frame.pose();
        apply(playback.prepareStop(PlaybackStopReason.MANUAL));
        assertEquals("say stopped", events.getLast().event().commands().getFirst());
        assertEquals(lastPose.timelineTick(), events.getLast().pose().timelineTick());
        assertEquals(lastPose.cursor().animationTick() == null ? 6 : lastPose.cursor().animationTick(), events.getLast().animationTick());
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
