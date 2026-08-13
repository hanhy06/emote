package io.github.hanhy06.emote.emote;

import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.sequence.EmoteSequence;
import com.google.gson.JsonPrimitive;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SequenceAnimationCompilerTest {
    private static final EmoteAnimation.Matrix IDENTITY = matrix(0.0D);

    @Test
    void compilesStepsRepeatsLoopDelayAndTimelineEventsIntoOneAnimation() {
        RegisteredEmote enter = animation(
            "demo:enter",
            2,
            EmoteAnimation.LoopMode.ONCE,
            0,
            List.of(keyframe(2, 2.0D)),
            EmoteAnimation.Events.empty(),
            Map.of("root", new EmoteAnimation.AnchorNode(IDENTITY))
        );
        RegisteredEmote idle = animation(
            "demo:idle",
            3,
            EmoteAnimation.LoopMode.LOOP,
            2,
            List.of(keyframe(3, 3.0D)),
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
            Map.of("root", new EmoteAnimation.AnchorNode(IDENTITY))
        );
        RegisteredSequence sequence = RegisteredSequence.resolve(
            sequence(
                new EmoteSequence.Step(Identifier.parse("demo:enter"), 1),
                new EmoteSequence.Step(Identifier.parse("demo:idle"), 2)
            ),
            Map.of(enter.id(), enter, idle.id(), idle)
        );

        EmoteAnimation compiled = sequence.compiledAnimation().animation();

        assertEquals("demo:sequence", compiled.id().toString());
        assertEquals(10, compiled.timeline().durationTicks());
        assertEquals(EmoteAnimation.LoopMode.ONCE, compiled.settings().playback().mode());
        assertEquals(List.of(0, 2, 7), compiled.timeline().keyframes().stream()
            .filter(keyframe -> !keyframe.nodeStates().isEmpty())
            .map(EmoteAnimation.Keyframe::tick)
            .toList());
        assertEquals(List.of(4, 9), compiled.timeline().events().timeline().stream()
            .map(EmoteAnimation.TimelineEvent::tick)
            .toList());
    }

    @Test
    void rejectsAnimationsWithDifferentNodeLayouts() {
        RegisteredEmote first = animation(
            "demo:first",
            1,
            EmoteAnimation.LoopMode.ONCE,
            0,
            List.of(),
            EmoteAnimation.Events.empty(),
            Map.of("root", new EmoteAnimation.AnchorNode(IDENTITY))
        );
        RegisteredEmote second = animation(
            "demo:second",
            1,
            EmoteAnimation.LoopMode.ONCE,
            0,
            List.of(),
            EmoteAnimation.Events.empty(),
            Map.of("other", new EmoteAnimation.AnchorNode(IDENTITY))
        );

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> RegisteredSequence.resolve(
            sequence(
                new EmoteSequence.Step(Identifier.parse(first.id()), 1),
                new EmoteSequence.Step(Identifier.parse(second.id()), 1)
            ),
            Map.of(first.id(), first, second.id(), second)
        ));

        assertEquals(
            "Sequence animations must use compatible nodes: demo:first and demo:second",
            exception.getMessage()
        );
    }

    @Test
    void rejectsLifecycleEventsWhoseStepBoundaryMeaningWouldBeLost() {
        EmoteAnimation.Event startEvent = new EmoteAnimation.Event(
            new EmoteAnimation.CommandSource(EmoteAnimation.SourceType.SERVER, null),
            new EmoteAnimation.CommandOrigin(EmoteAnimation.OriginType.ROOT, null, EmoteAnimation.Vec3.ZERO),
            List.of("say start")
        );
        RegisteredEmote animation = animation(
            "demo:eventful",
            1,
            EmoteAnimation.LoopMode.ONCE,
            0,
            List.of(),
            new EmoteAnimation.Events(List.of(startEvent), List.of(), List.of(), List.of()),
            Map.of("root", new EmoteAnimation.AnchorNode(IDENTITY))
        );

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> RegisteredSequence.resolve(
            sequence(new EmoteSequence.Step(Identifier.parse(animation.id()), 1)),
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
            IDENTITY,
            new CompoundTag(),
            new JsonPrimitive("same")
        );
        RegisteredEmote first = animation(
            "demo:first",
            1,
            EmoteAnimation.LoopMode.ONCE,
            0,
            List.of(),
            EmoteAnimation.Events.empty(),
            Map.of("text", node),
            Map.of("text", new EmoteAnimation.PreparedTextData(Component.literal("same")))
        );
        RegisteredEmote second = animation(
            "demo:second",
            1,
            EmoteAnimation.LoopMode.ONCE,
            0,
            List.of(),
            EmoteAnimation.Events.empty(),
            Map.of("text", node),
            Map.of("text", new EmoteAnimation.PreparedTextData(Component.literal("same")))
        );

        RegisteredSequence sequence = RegisteredSequence.resolve(
            sequence(
                new EmoteSequence.Step(Identifier.parse(first.id()), 1),
                new EmoteSequence.Step(Identifier.parse(second.id()), 1)
            ),
            Map.of(first.id(), first, second.id(), second)
        );

        assertEquals(2, sequence.durationTicks());
    }

    @Test
    void randomCandidatesDoNotRepeatConsecutively() {
        RegisteredEmote first = animation(
            "demo:first",
            1,
            EmoteAnimation.LoopMode.ONCE,
            0,
            List.of(),
            EmoteAnimation.Events.empty(),
            Map.of("root", new EmoteAnimation.AnchorNode(IDENTITY))
        );
        RegisteredEmote second = animation(
            "demo:second",
            1,
            EmoteAnimation.LoopMode.ONCE,
            0,
            List.of(),
            EmoteAnimation.Events.empty(),
            Map.of("root", new EmoteAnimation.AnchorNode(IDENTITY))
        );
        RegisteredEmote third = animation(
            "demo:third",
            1,
            EmoteAnimation.LoopMode.ONCE,
            0,
            List.of(),
            EmoteAnimation.Events.empty(),
            Map.of("root", new EmoteAnimation.AnchorNode(IDENTITY))
        );
        EmoteSequence source = new EmoteSequence(
            Path.of("sequence.json"),
            Identifier.parse("demo:sequence"),
            new EmoteMetadata("Sequence", "Random sequence"),
            EmotePlayerBehavior.createDefault(),
            List.of(new EmoteSequence.Step(List.of(
                Identifier.parse(first.id()),
                Identifier.parse(second.id()),
                Identifier.parse(third.id())
            ), 20))
        );
        RegisteredSequence sequence = RegisteredSequence.resolve(
            source,
            Map.of(first.id(), first, second.id(), second, third.id(), third)
        );

        List<String> selectedIds = sequence.selectSteps(new Random(7L)).stream()
            .map(step -> step.animation().id())
            .toList();

        assertEquals(20, selectedIds.size());
        for (int index = 1; index < selectedIds.size(); index++) {
            org.junit.jupiter.api.Assertions.assertNotEquals(selectedIds.get(index - 1), selectedIds.get(index));
        }
    }

    @Test
    void selectsWeightedCandidatesAfterExcludingThePreviousCandidate() {
        RegisteredEmote first = animation("demo:first", 1, EmoteAnimation.LoopMode.ONCE, 0, List.of(), EmoteAnimation.Events.empty(), Map.of("root", new EmoteAnimation.AnchorNode(IDENTITY)));
        RegisteredEmote second = animation("demo:second", 1, EmoteAnimation.LoopMode.ONCE, 0, List.of(), EmoteAnimation.Events.empty(), Map.of("root", new EmoteAnimation.AnchorNode(IDENTITY)));
        RegisteredEmote third = animation("demo:third", 1, EmoteAnimation.LoopMode.ONCE, 0, List.of(), EmoteAnimation.Events.empty(), Map.of("root", new EmoteAnimation.AnchorNode(IDENTITY)));
        EmoteSequence source = new EmoteSequence(
            Path.of("sequence.json"),
            Identifier.parse("demo:sequence"),
            new EmoteMetadata("Sequence", "Weighted sequence"),
            EmotePlayerBehavior.createDefault(),
            List.of(new EmoteSequence.Step(List.of(
                new EmoteSequence.Choice(Identifier.parse(first.id()), 10),
                new EmoteSequence.Choice(Identifier.parse(second.id()), 20),
                new EmoteSequence.Choice(Identifier.parse(third.id()), 70)
            ), 3))
        );
        RegisteredSequence sequence = RegisteredSequence.resolve(source, Map.of(first.id(), first, second.id(), second, third.id(), third));
        int[] randomValues = {15, 0, 89};
        AtomicInteger randomIndex = new AtomicInteger();
        Random random = new Random() {
            @Override
            public int nextInt(int bound) {
                return randomValues[randomIndex.getAndIncrement()];
            }
        };

        List<String> selectedIds = sequence.selectSteps(random).stream().map(step -> step.animation().id()).toList();

        assertEquals(List.of("demo:second", "demo:first", "demo:third"), selectedIds);
    }

    private static EmoteSequence sequence(EmoteSequence.Step... steps) {
        return new EmoteSequence(
            Path.of("sequence.json"),
            Identifier.parse("demo:sequence"),
            new EmoteMetadata("Sequence", "Compiled sequence"),
            EmotePlayerBehavior.createDefault(),
            List.of(steps)
        );
    }

    private static RegisteredEmote animation(
        String id,
        int duration,
        EmoteAnimation.LoopMode loop,
        int loopDelay,
        List<EmoteAnimation.Keyframe> keyframes,
        EmoteAnimation.Events events,
        Map<String, EmoteAnimation.Node> nodes
    ) {
        return animation(id, duration, loop, loopDelay, keyframes, events, nodes, Map.of());
    }

    private static RegisteredEmote animation(
        String id,
        int duration,
        EmoteAnimation.LoopMode loop,
        int loopDelay,
        List<EmoteAnimation.Keyframe> keyframes,
        EmoteAnimation.Events events,
        Map<String, EmoteAnimation.Node> nodes,
        Map<String, EmoteAnimation.PreparedDisplayData> preparedDisplayData
    ) {
        EmoteAnimation animation = new EmoteAnimation(
            Identifier.parse(id),
            new EmoteMetadata(id, id),
            new EmoteAnimation.Settings(false, 0, EmotePlayerBehavior.createDefault(), new EmoteAnimation.PlaybackSettings(loop, loopDelay)),
            nodes,
            new EmoteAnimation.Timeline(duration, keyframes, events)
        );
        return RegisteredEmote.from(new EmoteAnimation.Loaded(
            Path.of(id.replace(':', '_') + ".json"),
            id,
            animation,
            preparedDisplayData
        ));
    }

    private static EmoteAnimation.Keyframe keyframe(int tick, double x) {
        return new EmoteAnimation.Keyframe(
            tick,
            Map.of("root", new EmoteAnimation.NodeTransform(matrix(x), 0)),
            Map.of()
        );
    }

    private static EmoteAnimation.Matrix matrix(double x) {
        return new EmoteAnimation.Matrix(List.of(
            1.0D, 0.0D, 0.0D, x,
            0.0D, 1.0D, 0.0D, 0.0D,
            0.0D, 0.0D, 1.0D, 0.0D,
            0.0D, 0.0D, 0.0D, 1.0D
        ));
    }
}
