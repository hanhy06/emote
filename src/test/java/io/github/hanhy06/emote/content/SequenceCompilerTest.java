package io.github.hanhy06.emote.content;

import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.StringReader;
import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.ParticipantRole;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import net.minecraft.commands.arguments.coordinates.RotationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
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
    void keepsThePreviousPoseDuringAnExplicitWaitStep() {
        PreparedAnimation first = animation("demo:first", 2, EmoteAnimation.LoopMode.ONCE, 0, Map.of(), EmoteAnimation.Events.empty(), Map.of("root", sceneAnchor()));
        PreparedAnimation second = animation("demo:second", 3, EmoteAnimation.LoopMode.ONCE, 0, Map.of(), EmoteAnimation.Events.empty(), Map.of("root", sceneAnchor()));
        PreparedSequence sequence = PreparedSequence.resolve(
            sequence(
                new EmoteSequence.EmoteStep(Identifier.parse(first.id()), 1),
                new EmoteSequence.WaitStep(5),
                new EmoteSequence.EmoteStep(Identifier.parse(second.id()), 1)
            ),
            Map.of(first.id(), first, second.id(), second)
        );

        PreparedAnimation compiled = sequence.compiledAnimation();

        assertEquals(10, compiled.animation().timeline().durationTicks());
        assertEquals(List.of(0, 7), compiled.playbackSegments().stream().map(PreparedAnimation.PlaybackSegment::startTick).toList());
    }

    @Test
    void compilesStepsRepeatsLoopDelayAndTimelineEventsIntoOneAnimation() {
        PreparedAnimation enter = animation(
            "demo:enter",
            2,
            EmoteAnimation.LoopMode.ONCE,
            0,
            Map.of("root", positionTrack(2, 2.0D)),
            EmoteAnimation.Events.empty(),
            Map.of("root", sceneAnchor())
        );
        PreparedAnimation idle = animation(
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
                    new EmoteAnimation.CommandOrigin(EmoteAnimation.OriginType.ROOT, null, EmoteAnimation.Vec3.ZERO),
                    List.of("say idle")
                )),
                List.of(),
                List.of()
            ),
            Map.of("root", sceneAnchor())
        );
        PreparedSequence sequence = PreparedSequence.resolve(
            sequence(
                new EmoteSequence.EmoteStep(Identifier.parse("demo:enter"), 1),
                new EmoteSequence.EmoteStep(Identifier.parse("demo:idle"), 2)
            ),
            Map.of(enter.id(), enter, idle.id(), idle)
        );

        PreparedAnimation compiledPlan = sequence.compiledAnimation();
        EmoteAnimation compiled = compiledPlan.animation();

        assertEquals("demo:sequence", compiled.id().toString());
        assertEquals(10, compiled.timeline().durationTicks());
        assertEquals(EmoteAnimation.LoopMode.ONCE, compiled.settings().playback().mode());
        assertEquals(List.of(0, 2, 7), compiledPlan.playbackSegments().stream()
            .map(PreparedAnimation.PlaybackSegment::startTick).toList());
        assertEquals(List.of(4, 9), compiled.timeline().events().timeline().stream()
            .map(EmoteAnimation.TimelineEvent::tick)
            .toList());
    }

    @Test
    void createsAllCandidateNodesAtTheirOwnInitialPositionsAndSwitchesVisibility() {
        EmoteAnimation.TextNode flowerNode = new EmoteAnimation.TextNode(
            true,
            EmoteAnimation.NodeSpace.SCENE,
            null,
            transform(2.0D),
            new CompoundTag(),
            new JsonPrimitive("flower")
        );
        EmoteAnimation.TextNode butterflyNode = new EmoteAnimation.TextNode(
            true,
            EmoteAnimation.NodeSpace.SCENE,
            null,
            transform(8.0D),
            new CompoundTag(),
            new JsonPrimitive("butterfly")
        );
        PreparedAnimation first = animation(
            "demo:first",
            1,
            EmoteAnimation.LoopMode.ONCE,
            0,
            Map.of(),
            EmoteAnimation.Events.empty(),
            Map.of("flower", flowerNode),
            Map.of("flower", new PreparedDisplayData.Text(Component.literal("flower")))
        );
        PreparedAnimation second = animation(
            "demo:second",
            1,
            EmoteAnimation.LoopMode.ONCE,
            0,
            Map.of(),
            EmoteAnimation.Events.empty(),
            Map.of("butterfly", butterflyNode),
            Map.of("butterfly", new PreparedDisplayData.Text(Component.literal("butterfly")))
        );
        PreparedSequence sequence = PreparedSequence.resolve(
            sequence(
                new EmoteSequence.EmoteStep(List.of(
                    Identifier.parse(first.id()),
                    Identifier.parse(second.id())
                ), 2)
            ),
            Map.of(first.id(), first, second.id(), second)
        );

        PreparedAnimation compiledPlan = sequence.compiledAnimation();
        EmoteAnimation compiled = compiledPlan.animation();

        assertEquals(Set.of("flower", "butterfly"), compiled.nodes().keySet());
        assertEquals(transform(2.0D), compiled.nodes().get("flower").transform());
        assertEquals(transform(8.0D), compiled.nodes().get("butterfly").transform());
        assertFalse(compiledPlan.hiddenNodes(0).contains("flower"));
        assertTrue(compiledPlan.hiddenNodes(0).contains("butterfly"));

        PreparedAnimation alternating = sequence.compile(randomWithValues(0, 0));
        assertTrue(alternating.hiddenNodes(1).contains("flower"));
        assertFalse(alternating.hiddenNodes(1).contains("butterfly"));
    }

    @Test
    void rejectsLifecycleEventsWhoseStepBoundaryMeaningWouldBeLost() {
        EmoteAnimation.Event startEvent = new EmoteAnimation.Event(
            new EmoteAnimation.CommandSource(EmoteAnimation.SourceType.SERVER, null),
            new EmoteAnimation.CommandOrigin(EmoteAnimation.OriginType.ROOT, null, EmoteAnimation.Vec3.ZERO),
            List.of("say start")
        );
        PreparedAnimation animation = animation(
            "demo:eventful",
            1,
            EmoteAnimation.LoopMode.ONCE,
            0,
            Map.of(),
            new EmoteAnimation.Events(List.of(startEvent), List.of(), List.of(), List.of()),
            Map.of("root", sceneAnchor())
        );

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> PreparedSequence.resolve(
            sequence(new EmoteSequence.EmoteStep(Identifier.parse(animation.id()), 1)),
            Map.of(animation.id(), animation)
        ));

        assertEquals(
            "Sequence animation lifecycle events are not supported by compiled sequences: demo:eventful",
            exception.getMessage()
        );
    }

    @Test
    void acceptsEquivalentDisplayContentPreparedAsSeparateRuntimeObjects() {
        EmoteAnimation.TextNode node = new EmoteAnimation.TextNode(
            true,
            EmoteAnimation.NodeSpace.SCENE,
            null,
            EmoteAnimation.LocalTransform.IDENTITY,
            new CompoundTag(),
            new JsonPrimitive("same")
        );
        PreparedAnimation first = animation(
            "demo:first",
            1,
            EmoteAnimation.LoopMode.ONCE,
            0,
            Map.of(),
            EmoteAnimation.Events.empty(),
            Map.of("text", node),
            Map.of("text", new PreparedDisplayData.Text(Component.literal("same")))
        );
        PreparedAnimation second = animation(
            "demo:second",
            1,
            EmoteAnimation.LoopMode.ONCE,
            0,
            Map.of(),
            EmoteAnimation.Events.empty(),
            Map.of("text", node),
            Map.of("text", new PreparedDisplayData.Text(Component.literal("same")))
        );

        PreparedSequence sequence = PreparedSequence.resolve(
            sequence(
                new EmoteSequence.EmoteStep(Identifier.parse(first.id()), 1),
                new EmoteSequence.EmoteStep(Identifier.parse(second.id()), 1)
            ),
            Map.of(first.id(), first, second.id(), second)
        );

        assertEquals(2, sequence.durationTicks());
    }

    @Test
    void randomCandidatesDoNotRepeatConsecutively() {
        PreparedAnimation first = animation(
            "demo:first",
            1,
            EmoteAnimation.LoopMode.ONCE,
            0,
            Map.of(),
            EmoteAnimation.Events.empty(),
            Map.of("root", sceneAnchor())
        );
        PreparedAnimation second = animation(
            "demo:second",
            1,
            EmoteAnimation.LoopMode.ONCE,
            0,
            Map.of(),
            EmoteAnimation.Events.empty(),
            Map.of("root", sceneAnchor())
        );
        PreparedAnimation third = animation(
            "demo:third",
            1,
            EmoteAnimation.LoopMode.ONCE,
            0,
            Map.of(),
            EmoteAnimation.Events.empty(),
            Map.of("root", sceneAnchor())
        );
        EmoteSequence source = new EmoteSequence(
            Path.of("sequence.json"),
            Identifier.parse("demo:sequence"),
            new EmoteMetadata("Sequence", "Random sequence"),
            new EmoteSequence.Settings(0, EmotePlayerBehavior.createDefault()),
            List.of(new EmoteSequence.EmoteStep(List.of(
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
            .map(PreparedSequence.SelectedEmoteStep.class::cast)
            .map(step -> step.animation().id())
            .toList();

        assertEquals(20, selectedIds.size());
        for (int index = 1; index < selectedIds.size(); index++) {
            org.junit.jupiter.api.Assertions.assertNotEquals(selectedIds.get(index - 1), selectedIds.get(index));
        }
    }

    @Test
    void selectsWeightedCandidatesAfterExcludingThePreviousCandidate() {
        PreparedAnimation first = animation("demo:first", 1, EmoteAnimation.LoopMode.ONCE, 0, Map.of(), EmoteAnimation.Events.empty(), Map.of("root", sceneAnchor()));
        PreparedAnimation second = animation("demo:second", 1, EmoteAnimation.LoopMode.ONCE, 0, Map.of(), EmoteAnimation.Events.empty(), Map.of("root", sceneAnchor()));
        PreparedAnimation third = animation("demo:third", 1, EmoteAnimation.LoopMode.ONCE, 0, Map.of(), EmoteAnimation.Events.empty(), Map.of("root", sceneAnchor()));
        EmoteSequence source = new EmoteSequence(
            Path.of("sequence.json"),
            Identifier.parse("demo:sequence"),
            new EmoteMetadata("Sequence", "Weighted sequence"),
            new EmoteSequence.Settings(0, EmotePlayerBehavior.createDefault()),
            List.of(new EmoteSequence.EmoteStep(List.of(
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
            .map(PreparedSequence.SelectedEmoteStep.class::cast)
            .map(step -> step.animation().id())
            .toList();

        assertEquals(List.of("demo:second", "demo:first", "demo:third"), selectedIds);
    }

    @Test
    void continueSkipsOneIterationAndBreakStopsOnlyTheCurrentRepeat() {
        PreparedAnimation loop = animation("demo:loop", 2, EmoteAnimation.LoopMode.LOOP, 4, Map.of(), EmoteAnimation.Events.empty(), Map.of("root", sceneAnchor()));
        PreparedAnimation finish = animation("demo:finish", 3, EmoteAnimation.LoopMode.ONCE, 0, Map.of(), EmoteAnimation.Events.empty(), Map.of("root", sceneAnchor()));
        EmoteSequence source = new EmoteSequence(
            Path.of("sequence.json"),
            Identifier.parse("demo:sequence"),
            new EmoteMetadata("Sequence", "Control sequence"),
            new EmoteSequence.Settings(0, EmotePlayerBehavior.createDefault()),
            List.of(
                new EmoteSequence.EmoteStep(List.of(
                    new EmoteSequence.Choice(Identifier.parse(loop.id()), 0),
                    new EmoteSequence.Choice(EmoteSequence.Control.CONTINUE.id(), 0),
                    new EmoteSequence.Choice(EmoteSequence.Control.BREAK.id(), 0)
                ), 6),
                new EmoteSequence.EmoteStep(Identifier.parse(finish.id()), 1)
            )
        );
        PreparedSequence sequence = PreparedSequence.resolve(source, Map.of(loop.id(), loop, finish.id(), finish));
        int[] randomValues = {0, 1, 0, 2};

        List<PreparedSequence.SelectedStep> selected = sequence.selectSteps(randomWithValues(randomValues));

        List<PreparedSequence.SelectedEmoteStep> animations = selected.stream()
            .map(PreparedSequence.SelectedEmoteStep.class::cast)
            .toList();
        assertEquals(List.of("demo:loop", "demo:loop", "demo:finish"), animations.stream().map(step -> step.animation().id()).toList());
        assertEquals(List.of(true, false, false), animations.stream().map(PreparedSequence.SelectedEmoteStep::loopDelayAfter).toList());
        assertEquals(11, sequence.compile(randomWithValues(randomValues)).durationTicks());
    }

    @Test
    void controlChoicesDoNotForceTheOnlyAnimationToAlternateWithContinue() {
        PreparedAnimation animation = animation("demo:only", 1, EmoteAnimation.LoopMode.ONCE, 0, Map.of(), EmoteAnimation.Events.empty(), Map.of("root", sceneAnchor()));
        EmoteSequence source = new EmoteSequence(
            Path.of("sequence.json"),
            Identifier.parse("demo:sequence"),
            new EmoteMetadata("Sequence", "Control sequence"),
            new EmoteSequence.Settings(0, EmotePlayerBehavior.createDefault()),
            List.of(new EmoteSequence.EmoteStep(List.of(
                new EmoteSequence.Choice(Identifier.parse(animation.id()), 0),
                new EmoteSequence.Choice(EmoteSequence.Control.CONTINUE.id(), 0)
            ), 3))
        );
        PreparedSequence sequence = PreparedSequence.resolve(source, Map.of(animation.id(), animation));

        List<String> selectedIds = sequence.selectSteps(randomWithValues(0, 0, 0)).stream()
            .map(PreparedSequence.SelectedEmoteStep.class::cast)
            .map(step -> step.animation().id())
            .toList();

        assertEquals(List.of("demo:only", "demo:only", "demo:only"), selectedIds);
    }

    @Test
    void compilesAnEmptyControlResultAsAHiddenOneTickTimeline() {
        PreparedAnimation animation = animation("demo:anchor", 2, EmoteAnimation.LoopMode.ONCE, 0, Map.of(), EmoteAnimation.Events.empty(), Map.of("root", sceneAnchor()));
        EmoteSequence source = new EmoteSequence(
            Path.of("sequence.json"),
            Identifier.parse("demo:sequence"),
            new EmoteMetadata("Sequence", "Control sequence"),
            new EmoteSequence.Settings(0, EmotePlayerBehavior.createDefault()),
            List.of(new EmoteSequence.EmoteStep(List.of(
                new EmoteSequence.Choice(EmoteSequence.Control.CONTINUE.id(), 0),
                new EmoteSequence.Choice(Identifier.parse(animation.id()), 0)
            ), 1))
        );
        PreparedSequence sequence = PreparedSequence.resolve(source, Map.of(animation.id(), animation));

        EmoteAnimation compiled = sequence.compile(randomWithValues(0)).animation();

        assertEquals(1, compiled.timeline().durationTicks());
        assertTrue(sequence.compile(randomWithValues(0)).hiddenNodes(0).contains("root"));
    }

    @Test
    void rejectsASequenceWithoutAnyAnimationCandidate() {
        EmoteSequence source = new EmoteSequence(
            Path.of("sequence.json"),
            Identifier.parse("demo:sequence"),
            new EmoteMetadata("Sequence", "Control sequence"),
            new EmoteSequence.Settings(0, EmotePlayerBehavior.createDefault()),
            List.of(new EmoteSequence.EmoteStep(List.of(
                new EmoteSequence.Choice(EmoteSequence.Control.CONTINUE.id(), 0),
                new EmoteSequence.Choice(EmoteSequence.Control.BREAK.id(), 0)
            ), 3))
        );

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> PreparedSequence.resolve(source, Map.of()));

        assertEquals("Sequence must reference at least one animation", exception.getMessage());
    }

    @Test
    void automaticallyDuplicatesInitiatorNodesWhenPartnerNodesAreAbsent() throws Exception {
        PreparedAnimation animation = animation(
            "demo:handshake",
            2,
            EmoteAnimation.LoopMode.ONCE,
            0,
            Map.of("body", new EmoteAnimation.NodeTracks(
                List.of(new EmoteAnimation.VectorKeyframe(
                    1,
                    vector(1.0D),
                    vector(1.0D),
                    EmoteAnimation.Interpolation.LINEAR,
                    EmoteAnimation.Easing.LINEAR
                )),
                List.of(),
                List.of(),
                List.of(new EmoteAnimation.VisibilityKeyframe(1, new EmoteAnimation.ConstantVisibility(false)))
            )),
            EmoteAnimation.Events.empty(),
            Map.of("body", new EmoteAnimation.ItemNode(
                true,
                EmoteAnimation.NodeSpace.INITIATOR,
                null,
                EmoteAnimation.LocalTransform.IDENTITY,
                new CompoundTag(),
                new EmoteAnimation.FixedItemSource(new CompoundTag()),
                "none",
                new EmoteAnimation.Skin(ParticipantRole.INITIATOR, EmoteAnimation.SkinPart.BODY, 0)
            ))
        );
        PreparedSequence sequence = PreparedSequence.resolve(
            partnerSequence(animation.id()),
            Map.of(animation.id(), animation)
        );

        PreparedAnimation compiledEmote = sequence.compileMatch(new Random(1L));
        EmoteAnimation compiled = compiledEmote.animation();
        String partnerId = compiled.nodes().keySet().stream().filter(id -> !id.equals("body")).findFirst().orElseThrow();

        assertEquals(sequence.compiledAnimation().animation().nodes().keySet(), compiled.nodes().keySet());
        assertEquals(1, sequence.compiledAnimation().skinBindings(ParticipantRole.PARTNER).size());
        assertEquals(EmoteAnimation.NodeSpace.PARTNER, compiled.nodes().get(partnerId).space());
        assertEquals(1, compiledEmote.skinBindings(ParticipantRole.PARTNER).size());
        PreparedAnimation.PlaybackSegment segment = compiledEmote.playbackSegments().getFirst();
        assertTrue(segment.animation().animation().timeline().tracks().containsKey("body"));
        assertEquals(partnerId, segment.mirroredNodes().get("body"));
    }

    @Test
    void keepsExplicitPartnerNodesWithoutGeneratingAnotherCopy() throws Exception {
        PreparedAnimation animation = animation(
            "demo:hug",
            2,
            EmoteAnimation.LoopMode.ONCE,
            0,
            Map.of(),
            EmoteAnimation.Events.empty(),
            Map.of(
                "giver", new EmoteAnimation.AnchorNode(EmoteAnimation.NodeSpace.INITIATOR, null, EmoteAnimation.LocalTransform.IDENTITY),
                "receiver", new EmoteAnimation.AnchorNode(EmoteAnimation.NodeSpace.PARTNER, null, EmoteAnimation.LocalTransform.IDENTITY)
            )
        );
        PreparedSequence sequence = PreparedSequence.resolve(
            partnerSequence(animation.id()),
            Map.of(animation.id(), animation)
        );

        EmoteAnimation compiled = sequence.compileMatch(new Random(1L)).animation();

        assertEquals(Set.of("giver", "receiver"), compiled.nodes().keySet());
        assertFalse(compiled.nodes().keySet().stream().anyMatch(id -> id.startsWith("__partner__")));
    }

    private static EmoteSequence sequence(EmoteSequence.Step... steps) {
        return new EmoteSequence(
            Path.of("sequence.json"),
            Identifier.parse("demo:sequence"),
            new EmoteMetadata("Sequence", "Compiled sequence"),
            new EmoteSequence.Settings(0, EmotePlayerBehavior.createDefault()),
            List.of(steps)
        );
    }

    private static EmoteSequence partnerSequence(String animationId) throws Exception {
        EmoteSequence.ParticipantPlacement initiator = new EmoteSequence.ParticipantPlacement(
            Vec3Argument.vec3(false).parse(new StringReader("~ ~ ~")),
            RotationArgument.rotation().parse(new StringReader("~ 0"))
        );
        EmoteSequence.ParticipantPlacement partner = new EmoteSequence.ParticipantPlacement(
            Vec3Argument.vec3(false).parse(new StringReader("^ ^ ^1.2")),
            RotationArgument.rotation().parse(new StringReader("~180 0"))
        );
        Identifier id = Identifier.parse(animationId);
        return new EmoteSequence(
            Path.of("partner.json"),
            Identifier.parse("demo:partner"),
            new EmoteMetadata("Partner", "Two-player sequence"),
            new EmoteSequence.Settings(0, EmotePlayerBehavior.createDefault()),
            new EmoteSequence.Participants(initiator, partner),
            List.of(new EmoteSequence.AwaitPartnerStep(
                id,
                20,
                List.of(new EmoteSequence.EmoteStep(id, 1)),
                List.of(new EmoteSequence.EmoteStep(id, 1))
            ))
        );
    }

    private static PreparedAnimation animation(
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
        return new EmoteAnimation.AnchorNode(EmoteAnimation.NodeSpace.SCENE, null, EmoteAnimation.LocalTransform.IDENTITY);
    }

    private static PreparedAnimation animation(
        String id,
        int duration,
        EmoteAnimation.LoopMode loop,
        int loopDelay,
        Map<String, EmoteAnimation.NodeTracks> tracks,
        EmoteAnimation.Events events,
        Map<String, EmoteAnimation.Node> nodes,
        Map<String, PreparedDisplayData> preparedDisplayData
    ) {
        EmoteAnimation animation = new EmoteAnimation(
            Identifier.parse(id),
            new EmoteMetadata(id, id),
            new EmoteAnimation.Settings(false, 0, EmotePlayerBehavior.createDefault(), new EmoteAnimation.PlaybackSettings(loop, loopDelay)),
            EmoteAnimation.MolangPrograms.empty(),
            nodes,
            new EmoteAnimation.Timeline(duration, tracks, events)
        );
        return PreparedAnimation.from(new LoadedAnimation(
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
            List.of()
        );
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
            new EmoteAnimation.Vec3(x, 0.0D, 0.0D),
            EmoteAnimation.Vec3.ZERO,
            new EmoteAnimation.Vec3(1.0D, 1.0D, 1.0D)
        );
    }
}
