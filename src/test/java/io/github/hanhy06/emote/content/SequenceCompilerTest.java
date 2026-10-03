package io.github.hanhy06.emote.content;

import net.minecraft.world.phys.Vec3;
import io.github.hanhy06.emote.api.sequence.EmoteSequence;

import com.google.gson.JsonPrimitive;
import com.mojang.math.Transformation;
import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.playback.PlaybackPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SequenceCompilerTest {

    @Test
    void insertsLinearTransitionBeforeEveryAnimationAfterTheFirst() {
        PreparedEmote first = animation("demo:first", 2, EmoteAnimation.LoopMode.ONCE, 0, Map.of(), EmoteAnimation.Events.empty(), Map.of("root", sceneAnchor()));
        EmoteAnimation.TimelineEvent event = new EmoteAnimation.TimelineEvent(
            1,
            new EmoteAnimation.CommandSource(EmoteAnimation.SourceType.SERVER, null),
            new EmoteAnimation.CommandOrigin(EmoteAnimation.OriginType.ROOT, null, Vec3.ZERO),
            List.of("say transitioned"));
        PreparedEmote second = animation(
            "demo:second",
            3,
            EmoteAnimation.LoopMode.ONCE,
            0,
            Map.of(),
            new EmoteAnimation.Events(List.of(), List.of(event), List.of(), List.of()),
            Map.of("root", sceneAnchor())
        );
        PreparedSequence sequence = PreparedSequence.resolve(
            sequence(
                new EmoteSequence.AnimationStep(Identifier.parse(first.id()), 1, 4),
                new EmoteSequence.AnimationStep(Identifier.parse(second.id()), 1, 4)
            ),
            Map.of(first.id(), first, second.id(), second)
        );

        PreparedEmote compiled = sequence.compiledEmote();

        assertEquals(9, compiled.durationTicks());
        assertEquals(List.of(0, 2), compiled.playbackSegments().stream()
            .map(PreparedEmote.PlaybackSegment::transitionStartTick).toList());
        assertEquals(List.of(0, 6), compiled.playbackSegments().stream()
            .map(PreparedEmote.PlaybackSegment::startTick).toList());
        List<Integer> eventTicks = new java.util.ArrayList<>();
        PlaybackPlayer player = new PlaybackPlayer(compiled, new EmptyTimelineTarget());
        player.bindEvents(executedEvent -> eventTicks.add(player.currentTick()));
        player.start();
        player.startEvents();
        while (player.advance() != PlaybackPlayer.AdvanceResult.FINISHED) {}
        assertEquals(List.of(7), eventTicks);
    }

    @Test
    void keepsThePreviousPoseDuringAnExplicitWaitStep() {
        PreparedEmote first = animation("demo:first", 2, EmoteAnimation.LoopMode.ONCE, 0, Map.of(), EmoteAnimation.Events.empty(), Map.of("root", sceneAnchor()));
        PreparedEmote second = animation("demo:second", 3, EmoteAnimation.LoopMode.ONCE, 0, Map.of(), EmoteAnimation.Events.empty(), Map.of("root", sceneAnchor()));
        PreparedSequence sequence = PreparedSequence.resolve(
            sequence(
                new EmoteSequence.AnimationStep(Identifier.parse(first.id()), 1),
                new EmoteSequence.WaitStep(5),
                new EmoteSequence.AnimationStep(Identifier.parse(second.id()), 1)
            ),
            Map.of(first.id(), first, second.id(), second)
        );

        PreparedEmote compiled = sequence.compiledEmote();

        assertEquals(10, compiled.model().timeline().durationTicks());
        assertEquals(List.of(0, 7), compiled.playbackSegments().stream().map(PreparedEmote.PlaybackSegment::startTick).toList());
    }

    @Test
    void compilesStepsRepeatsLoopDelayAndTimelineEventsIntoOneAnimation() {
        PreparedEmote enter = animation(
            "demo:enter",
            2,
            EmoteAnimation.LoopMode.ONCE,
            0,
            Map.of("root", positionTrack(2, 2.0D)),
            EmoteAnimation.Events.empty(),
            Map.of("root", sceneAnchor())
        );
        PreparedEmote idle = animation(
            "demo:idle",
            3,
            EmoteAnimation.LoopMode.LOOP,
            2,
            Map.of("root", positionTrack(3, 3.0D)),
            new EmoteAnimation.Events(
                List.of(),
                List.of(new EmoteAnimation.TimelineEvent(
                    2,
                    new EmoteAnimation.CommandSource(EmoteAnimation.SourceType.SERVER, null),
                    new EmoteAnimation.CommandOrigin(EmoteAnimation.OriginType.ROOT, null, Vec3.ZERO),
                    List.of("say idle"))),
                List.of(),
                List.of()
            ),
            Map.of("root", sceneAnchor())
        );
        PreparedSequence sequence = PreparedSequence.resolve(
            sequence(
                new EmoteSequence.AnimationStep(Identifier.parse("demo:enter"), 1),
                new EmoteSequence.AnimationStep(Identifier.parse("demo:idle"), 2)
            ),
            Map.of(enter.id(), enter, idle.id(), idle)
        );

        PreparedEmote compiledPlan = sequence.compiledEmote();
        EmoteAnimation compiled = compiledPlan.model();

        assertEquals("demo:sequence", compiled.id().toString());
        assertEquals(10, compiled.timeline().durationTicks());
        assertEquals(EmoteAnimation.LoopMode.ONCE, compiled.settings().playback().mode());
        assertEquals(List.of(0, 2, 7), compiledPlan.playbackSegments().stream()
            .map(PreparedEmote.PlaybackSegment::startTick).toList());
        List<Integer> eventTicks = new java.util.ArrayList<>();
        PlaybackPlayer player = new PlaybackPlayer(compiledPlan, new EmptyTimelineTarget());
        player.bindEvents(executedEvent -> eventTicks.add(player.currentTick()));
        player.start();
        player.startEvents();
        while (player.advance() != PlaybackPlayer.AdvanceResult.FINISHED) {}
        assertEquals(List.of(4, 9), eventTicks);
    }

    @Test
    void createsAllCandidateNodesAtTheirOwnInitialPositionsAndSwitchesVisibility() {
        EmoteAnimation.TextNode flowerNode = new EmoteAnimation.TextNode(
            true,
            null,
            transform(2.0D),
            new CompoundTag(),
            new JsonPrimitive("flower")
        );
        EmoteAnimation.TextNode butterflyNode = new EmoteAnimation.TextNode(
            true,
            null,
            transform(8.0D),
            new CompoundTag(),
            new JsonPrimitive("butterfly")
        );
        PreparedEmote first = animation(
            "demo:first",
            1,
            EmoteAnimation.LoopMode.ONCE,
            0,
            Map.of(),
            EmoteAnimation.Events.empty(),
            Map.of("flower", flowerNode),
            Map.of("flower", new DisplayData.Text(Component.literal("flower")))
        );
        PreparedEmote second = animation(
            "demo:second",
            1,
            EmoteAnimation.LoopMode.ONCE,
            0,
            Map.of(),
            EmoteAnimation.Events.empty(),
            Map.of("butterfly", butterflyNode),
            Map.of("butterfly", new DisplayData.Text(Component.literal("butterfly")))
        );
        PreparedSequence sequence = PreparedSequence.resolve(
            sequence(
                new EmoteSequence.AnimationStep(List.of(
                    Identifier.parse(first.id()),
                    Identifier.parse(second.id())
                ), 2)
            ),
            Map.of(first.id(), first, second.id(), second)
        );

        PreparedEmote compiledPlan = sequence.compiledEmote();
        EmoteAnimation compiled = compiledPlan.model();

        assertEquals(Set.of("flower", "butterfly"), compiled.nodes().keySet());
        assertEquals(transform(2.0D), compiled.nodes().get("flower").transform());
        assertEquals(transform(8.0D), compiled.nodes().get("butterfly").transform());
        assertFalse(compiledPlan.hiddenNodes(0).contains("flower"));
        assertTrue(compiledPlan.hiddenNodes(0).contains("butterfly"));

        PreparedEmote alternating = sequence.compile(randomWithValues(0, 0));
        assertTrue(alternating.hiddenNodes(1).contains("flower"));
        assertFalse(alternating.hiddenNodes(1).contains("butterfly"));
    }

    @Test
    void executesLifecycleEventsWithTheirSourceAnimationContext() {
        EmoteAnimation.Event startEvent = new EmoteAnimation.Event(
            new EmoteAnimation.CommandSource(EmoteAnimation.SourceType.SERVER, null),
            new EmoteAnimation.CommandOrigin(EmoteAnimation.OriginType.ROOT, null, Vec3.ZERO),
            List.of("say start"));
        EmoteAnimation.Event loopEvent = new EmoteAnimation.Event(
            startEvent.source(), startEvent.origin(), List.of("say loop"));
        EmoteAnimation.Event stopEvent = new EmoteAnimation.Event(
            startEvent.source(), startEvent.origin(), List.of("say stop"));
        PreparedEmote animation = animation(
            "demo:eventful",
            2,
            EmoteAnimation.LoopMode.LOOP,
            0,
            Map.of(),
            new EmoteAnimation.Events(List.of(startEvent), List.of(), List.of(loopEvent), List.of(stopEvent)),
            Map.of("root", sceneAnchor())
        );

        PreparedEmote compiled = PreparedSequence.resolve(
            sequence(new EmoteSequence.AnimationStep(Identifier.parse(animation.id()), 1)),
            Map.of(animation.id(), animation)
        ).compiledEmote();

        List<PreparedEmote.PreparedEvent> executed = new java.util.ArrayList<>();
        PlaybackPlayer player = new PlaybackPlayer(compiled, new EmptyTimelineTarget());
        player.bindEvents(executed::add);
        player.start();
        player.startEvents();
        player.advance();
        assertEquals(PlaybackPlayer.AdvanceResult.FINISHED, player.advance());
        player.stop();

        assertEquals(List.of(AnimationEventPhase.START, AnimationEventPhase.LOOP, AnimationEventPhase.STOP),
            executed.stream().map(PreparedEmote.PreparedEvent::phase).toList());
        assertTrue(executed.stream().allMatch(event -> event.animationId().equals(Identifier.parse("demo:eventful"))));
        assertEquals(List.of(0, 2, 2), executed.stream().map(PreparedEmote.PreparedEvent::animationTick).toList());
    }

    @Test
    void runsTheActiveInnerAnimationStopEventOnceWhenASequenceIsInterrupted() {
        EmoteAnimation.Event startEvent = new EmoteAnimation.Event(
            new EmoteAnimation.CommandSource(EmoteAnimation.SourceType.SERVER, null),
            new EmoteAnimation.CommandOrigin(EmoteAnimation.OriginType.ROOT, null, Vec3.ZERO),
            List.of("start"));
        EmoteAnimation.Event stopEvent = new EmoteAnimation.Event(
            startEvent.source(), startEvent.origin(), List.of("stop"));
        PreparedEmote animation = animation(
            "demo:interruptible",
            4,
            EmoteAnimation.LoopMode.ONCE,
            0,
            Map.of(),
            new EmoteAnimation.Events(List.of(startEvent), List.of(), List.of(), List.of(stopEvent)),
            Map.of()
        );
        PreparedEmote compiled = PreparedSequence.resolve(
            sequence(new EmoteSequence.AnimationStep(Identifier.parse(animation.id()), 1)),
            Map.of(animation.id(), animation)
        ).compiledEmote();
        List<PreparedEmote.PreparedEvent> executed = new java.util.ArrayList<>();
        PlaybackPlayer player = new PlaybackPlayer(compiled, new EmptyTimelineTarget());
        player.bindEvents(executed::add);

        player.start();
        player.startEvents();
        player.stop();
        player.stop();

        assertEquals(List.of(AnimationEventPhase.START, AnimationEventPhase.STOP), executed.stream().map(PreparedEmote.PreparedEvent::phase).toList());
        assertEquals(List.of(0, 0), executed.stream().map(PreparedEmote.PreparedEvent::animationTick).toList());
        assertTrue(executed.stream().allMatch(event -> event.animationId().equals(Identifier.parse("demo:interruptible"))));
    }

    @Test
    void acceptsEquivalentDisplayContentPreparedAsSeparateRuntimeObjects() {
        EmoteAnimation.TextNode node = new EmoteAnimation.TextNode(
            true,
            null,
            EmoteAnimation.LocalTransform.IDENTITY,
            new CompoundTag(),
            new JsonPrimitive("same")
        );
        PreparedEmote first = animation(
            "demo:first",
            1,
            EmoteAnimation.LoopMode.ONCE,
            0,
            Map.of(),
            EmoteAnimation.Events.empty(),
            Map.of("text", node),
            Map.of("text", new DisplayData.Text(Component.literal("same")))
        );
        PreparedEmote second = animation(
            "demo:second",
            1,
            EmoteAnimation.LoopMode.ONCE,
            0,
            Map.of(),
            EmoteAnimation.Events.empty(),
            Map.of("text", node),
            Map.of("text", new DisplayData.Text(Component.literal("same")))
        );

        PreparedSequence sequence = PreparedSequence.resolve(
            sequence(
                new EmoteSequence.AnimationStep(Identifier.parse(first.id()), 1),
                new EmoteSequence.AnimationStep(Identifier.parse(second.id()), 1)
            ),
            Map.of(first.id(), first, second.id(), second)
        );

        assertEquals(2, sequence.durationTicks());
    }

    @Test
    void randomCandidatesDoNotRepeatConsecutively() {
        PreparedEmote first = animation(
            "demo:first",
            1,
            EmoteAnimation.LoopMode.ONCE,
            0,
            Map.of(),
            EmoteAnimation.Events.empty(),
            Map.of("root", sceneAnchor())
        );
        PreparedEmote second = animation(
            "demo:second",
            1,
            EmoteAnimation.LoopMode.ONCE,
            0,
            Map.of(),
            EmoteAnimation.Events.empty(),
            Map.of("root", sceneAnchor())
        );
        PreparedEmote third = animation(
            "demo:third",
            1,
            EmoteAnimation.LoopMode.ONCE,
            0,
            Map.of(),
            EmoteAnimation.Events.empty(),
            Map.of("root", sceneAnchor())
        );
        EmoteSequence source = new EmoteSequence(Identifier.parse("demo:sequence"),
            new EmoteMetadata("Sequence", "Random sequence"),
            new EmoteSequence.Settings(0, EmotePlayerBehavior.createDefault()),
            List.of(new EmoteSequence.AnimationStep(List.of(
                Identifier.parse(first.id()),
                Identifier.parse(second.id()),
                Identifier.parse(third.id())
            ), 20))
        );
        PreparedSequence sequence = PreparedSequence.resolve(
            source,
            Map.of(first.id(), first, second.id(), second, third.id(), third)
        );

        List<String> selectedIds = sequence.selectSteps(new Random(7L)).stream()
            .map(PreparedSequence.SelectedAnimationStep.class::cast)
            .map(step -> step.animation().id())
            .toList();

        assertEquals(20, selectedIds.size());
        for (int index = 1; index < selectedIds.size(); index++) {
            org.junit.jupiter.api.Assertions.assertNotEquals(selectedIds.get(index - 1), selectedIds.get(index));
        }
    }

    @Test
    void selectsWeightedCandidatesAfterExcludingThePreviousCandidate() {
        PreparedEmote first = animation("demo:first", 1, EmoteAnimation.LoopMode.ONCE, 0, Map.of(), EmoteAnimation.Events.empty(), Map.of("root", sceneAnchor()));
        PreparedEmote second = animation("demo:second", 1, EmoteAnimation.LoopMode.ONCE, 0, Map.of(), EmoteAnimation.Events.empty(), Map.of("root", sceneAnchor()));
        PreparedEmote third = animation("demo:third", 1, EmoteAnimation.LoopMode.ONCE, 0, Map.of(), EmoteAnimation.Events.empty(), Map.of("root", sceneAnchor()));
        EmoteSequence source = new EmoteSequence(Identifier.parse("demo:sequence"),
            new EmoteMetadata("Sequence", "Weighted sequence"),
            new EmoteSequence.Settings(0, EmotePlayerBehavior.createDefault()),
            List.of(new EmoteSequence.AnimationStep(List.of(
                new EmoteSequence.Choice(Identifier.parse(first.id()), 10),
                new EmoteSequence.Choice(Identifier.parse(second.id()), 20),
                new EmoteSequence.Choice(Identifier.parse(third.id()), 70)
            ), 3))
        );
        PreparedSequence sequence = PreparedSequence.resolve(source, Map.of(first.id(), first, second.id(), second, third.id(), third));
        int[] randomValues = {15, 0, 89};
        AtomicInteger randomIndex = new AtomicInteger();
        Random random = new Random() {
            @Override
            public int nextInt(int bound) {
                return randomValues[randomIndex.getAndIncrement()];
            }
        };

        List<String> selectedIds = sequence.selectSteps(random).stream()
            .map(PreparedSequence.SelectedAnimationStep.class::cast)
            .map(step -> step.animation().id())
            .toList();

        assertEquals(List.of("demo:second", "demo:first", "demo:third"), selectedIds);
    }

    @Test
    void continueSkipsOneIterationAndBreakStopsOnlyTheCurrentRepeat() {
        PreparedEmote loop = animation("demo:loop", 2, EmoteAnimation.LoopMode.LOOP, 4, Map.of(), EmoteAnimation.Events.empty(), Map.of("root", sceneAnchor()));
        PreparedEmote finish = animation("demo:finish", 3, EmoteAnimation.LoopMode.ONCE, 0, Map.of(), EmoteAnimation.Events.empty(), Map.of("root", sceneAnchor()));
        EmoteSequence source = new EmoteSequence(Identifier.parse("demo:sequence"),
            new EmoteMetadata("Sequence", "Control sequence"),
            new EmoteSequence.Settings(0, EmotePlayerBehavior.createDefault()),
            List.of(
                new EmoteSequence.AnimationStep(List.of(
                    new EmoteSequence.Choice(Identifier.parse(loop.id()), 0),
                    new EmoteSequence.Choice(EmoteSequence.Control.CONTINUE.id(), 0),
                    new EmoteSequence.Choice(EmoteSequence.Control.BREAK.id(), 0)
                ), 6),
                new EmoteSequence.AnimationStep(Identifier.parse(finish.id()), 1)
            )
        );
        PreparedSequence sequence = PreparedSequence.resolve(source, Map.of(loop.id(), loop, finish.id(), finish));
        int[] randomValues = {0, 1, 0, 2};

        List<PreparedSequence.SelectedStep> selected = sequence.selectSteps(randomWithValues(randomValues));

        List<PreparedSequence.SelectedAnimationStep> animations = selected.stream()
            .map(PreparedSequence.SelectedAnimationStep.class::cast)
            .toList();
        assertEquals(List.of("demo:loop", "demo:loop", "demo:finish"), animations.stream().map(step -> step.animation().id()).toList());
        assertEquals(List.of(true, false, false), animations.stream().map(PreparedSequence.SelectedAnimationStep::loopDelayAfter).toList());
        assertEquals(11, sequence.compile(randomWithValues(randomValues)).durationTicks());
    }

    @Test
    void controlChoicesDoNotForceTheOnlyAnimationToAlternateWithContinue() {
        PreparedEmote animation = animation("demo:only", 1, EmoteAnimation.LoopMode.ONCE, 0, Map.of(), EmoteAnimation.Events.empty(), Map.of("root", sceneAnchor()));
        EmoteSequence source = new EmoteSequence(Identifier.parse("demo:sequence"),
            new EmoteMetadata("Sequence", "Control sequence"),
            new EmoteSequence.Settings(0, EmotePlayerBehavior.createDefault()),
            List.of(new EmoteSequence.AnimationStep(List.of(
                new EmoteSequence.Choice(Identifier.parse(animation.id()), 0),
                new EmoteSequence.Choice(EmoteSequence.Control.CONTINUE.id(), 0)
            ), 3))
        );
        PreparedSequence sequence = PreparedSequence.resolve(source, Map.of(animation.id(), animation));

        List<String> selectedIds = sequence.selectSteps(randomWithValues(0, 0, 0)).stream()
            .map(PreparedSequence.SelectedAnimationStep.class::cast)
            .map(step -> step.animation().id())
            .toList();

        assertEquals(List.of("demo:only", "demo:only", "demo:only"), selectedIds);
    }

    @Test
    void compilesAnEmptyControlResultAsAHiddenOneTickTimeline() {
        PreparedEmote animation = animation("demo:anchor", 2, EmoteAnimation.LoopMode.ONCE, 0, Map.of(), EmoteAnimation.Events.empty(), Map.of("root", sceneAnchor()));
        EmoteSequence source = new EmoteSequence(Identifier.parse("demo:sequence"),
            new EmoteMetadata("Sequence", "Control sequence"),
            new EmoteSequence.Settings(0, EmotePlayerBehavior.createDefault()),
            List.of(new EmoteSequence.AnimationStep(List.of(
                new EmoteSequence.Choice(EmoteSequence.Control.CONTINUE.id(), 0),
                new EmoteSequence.Choice(Identifier.parse(animation.id()), 0)
            ), 1))
        );
        PreparedSequence sequence = PreparedSequence.resolve(source, Map.of(animation.id(), animation));

        EmoteAnimation compiled = sequence.compile(randomWithValues(0)).model();

        assertEquals(1, compiled.timeline().durationTicks());
        assertTrue(sequence.compile(randomWithValues(0)).hiddenNodes(0).contains("root"));
    }

    @Test
    void rejectsASequenceWithoutAnyAnimationCandidate() {
        EmoteSequence source = new EmoteSequence(Identifier.parse("demo:sequence"),
            new EmoteMetadata("Sequence", "Control sequence"),
            new EmoteSequence.Settings(0, EmotePlayerBehavior.createDefault()),
            List.of(new EmoteSequence.AnimationStep(List.of(
                new EmoteSequence.Choice(EmoteSequence.Control.CONTINUE.id(), 0),
                new EmoteSequence.Choice(EmoteSequence.Control.BREAK.id(), 0)
            ), 3))
        );

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> PreparedSequence.resolve(source, Map.of()));

        assertEquals("Sequence must reference at least one animation", exception.getMessage());
    }

    private static EmoteSequence sequence(EmoteSequence.Step... steps) {
        return new EmoteSequence(Identifier.parse("demo:sequence"),
            new EmoteMetadata("Sequence", "Compiled sequence"),
            new EmoteSequence.Settings(0, EmotePlayerBehavior.createDefault()),
            List.of(steps)
        );
    }

    private static PreparedEmote animation(
        String id,
        int duration,
        EmoteAnimation.LoopMode loop,
        int loopDelay,
        Map<String, EmoteAnimation.NodeTracks> tracks,
        EmoteAnimation.Events events,
        Map<String, EmoteAnimation.Node> nodes
    ) {
        return animation(id, duration, loop, loopDelay, tracks, events, nodes, Map.of());
    }

    private static Random randomWithValues(int... values) {
        AtomicInteger index = new AtomicInteger();
        return new Random() {
            @Override
            public int nextInt(int bound) {
                return values[index.getAndIncrement()];
            }
        };
    }

    private static EmoteAnimation.AnchorNode sceneAnchor() {
        return new EmoteAnimation.AnchorNode(null, EmoteAnimation.LocalTransform.IDENTITY);
    }

    private static PreparedEmote animation(
        String id,
        int duration,
        EmoteAnimation.LoopMode loop,
        int loopDelay,
        Map<String, EmoteAnimation.NodeTracks> tracks,
        EmoteAnimation.Events events,
        Map<String, EmoteAnimation.Node> nodes,
        Map<String, DisplayData> preparedDisplayData
    ) {
        EmoteAnimation animation = new EmoteAnimation(
            Identifier.parse(id),
            new EmoteMetadata(id, id),
            new EmoteAnimation.Settings(false, 0, 50.0F, 1, EmotePlayerBehavior.createDefault(), new EmoteAnimation.PlaybackSettings(
                loop,
                0,
                loopDelay
            )),
            EmoteAnimation.MolangPrograms.empty(),
            nodes,
            new EmoteAnimation.Timeline(duration, tracks, events), List.of());
        return PreparedEmote.from(new LoadedAnimation(
            Path.of(id.replace(':', '_') + ".json"),
            id,
            animation,
            preparedDisplayData
        ));
    }

    private static EmoteAnimation.NodeTracks positionTrack(int tick, double x) {
        EmoteAnimation.VectorValue value = vector(x);
        return new EmoteAnimation.NodeTracks(
            List.of(new EmoteAnimation.VectorKeyframe(
                tick,
                value,
                value,
                EmoteAnimation.Interpolation.STEP,
                EmoteAnimation.Easing.LINEAR
            )),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
    }

    private static final class EmptyTimelineTarget implements PlaybackPlayer.TimelineTarget {
        @Override
        public Transformation createTransformation(String nodeId, PreparedEmote.PreparedTransform transform) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void applyTransform(String nodeId, PreparedEmote.PreparedTransform transform, int interpolationDurationTicks) {
        }

        @Override
        public void setVisible(String nodeId, boolean visible) {
        }

        @Override
        public void applyNbt(String nodeId, CompoundTag nbt) {
        }

        @Override
        public void resetAll() {
        }
    }

    private static EmoteAnimation.VectorValue vector(double x) {
        return new EmoteAnimation.VectorValue(
            new EmoteAnimation.ConstantValue(x),
            new EmoteAnimation.ConstantValue(0.0D),
            new EmoteAnimation.ConstantValue(0.0D)
        );
    }

    private static EmoteAnimation.LocalTransform transform(double x) {
        return new EmoteAnimation.LocalTransform(
            new Vec3(x, 0.0D, 0.0D),
            Vec3.ZERO,
            new Vec3(1.0D, 1.0D, 1.0D)
        );
    }
}
