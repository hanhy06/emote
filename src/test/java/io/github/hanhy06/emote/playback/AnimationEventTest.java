package io.github.hanhy06.emote.playback;

import com.mojang.math.Transformation;
import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.content.CompiledTimeline;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnimationEventTest {
    @Test
    void startsTickZeroEventAtTimelineStart() {
        AnimationFixture fixture = fixture(4, EmoteAnimation.LoopMode.LOOP, 0);
        fixture.player().start();

        fixture.player().startEvents();

        assertEquals(List.of("start", "tick-0"), fixture.executed());
    }

    @Test
    void skipsTickZeroEventWhenServerSynchronizedTimelineStartsMidCycle() {
        AnimationFixture fixture = fixture(4, EmoteAnimation.LoopMode.SERVER_SYNC, 0);
        fixture.player().startSynchronized(2L);

        fixture.player().startEvents();

        assertEquals(List.of("start"), fixture.executed());
    }

    @Test
    void runsTickZeroEventOnceAfterDelayedLoopRestart() {
        AnimationFixture fixture = fixture(1, EmoteAnimation.LoopMode.LOOP, 2);
        fixture.player().start();
        fixture.player().startEvents();

        fixture.player().advance();
        fixture.player().advance();
        fixture.player().advance();

        assertEquals(List.of("start", "tick-0", "loop", "tick-0"), fixture.executed());
    }

    @Test
    void runsTickZeroEventOnceAfterImmediateLoopRestart() {
        AnimationFixture fixture = fixture(1, EmoteAnimation.LoopMode.LOOP, 0);
        fixture.player().start();
        fixture.player().startEvents();

        fixture.player().advance();

        assertEquals(List.of("start", "tick-0", "loop", "tick-0"), fixture.executed());
    }

    @Test
    void canHoldAtLoopBoundaryForSequenceRepeatCounting() {
        AnimationFixture fixture = fixture(1, EmoteAnimation.LoopMode.LOOP, 0);
        fixture.player().start();
        fixture.player().startEvents();

        AnimationPlayer.AdvanceResult result = fixture.player().advance(false);

        assertEquals(AnimationPlayer.AdvanceResult.LOOP_BOUNDARY, result);
        assertEquals(List.of("start", "tick-0", "loop"), fixture.executed());
    }

    @Test
    void runsStopEventsOnlyOnce() {
        AnimationFixture fixture = fixture(4, EmoteAnimation.LoopMode.LOOP, 0);
        fixture.player().start();
        fixture.player().startEvents();

        fixture.player().stop();
        fixture.player().stop();

        assertEquals(List.of("start", "tick-0", "stop"), fixture.executed());
    }

    private AnimationFixture fixture(int durationTicks, EmoteAnimation.LoopMode loopMode, int loopDelayTicks) {
        List<String> executed = new ArrayList<>();
        EmoteAnimation animation = animation(durationTicks, loopMode, loopDelayTicks);
        AnimationPlayer player = new AnimationPlayer(animation, new EmptyTimelineTarget());
        player.bindEvents(event -> executed.addAll(event.commands()));
        return new AnimationFixture(player, executed);
    }

    private EmoteAnimation animation(int durationTicks, EmoteAnimation.LoopMode loopMode, int loopDelayTicks) {
        EmoteAnimation.Events events = new EmoteAnimation.Events(
            List.of(event("start")),
            List.of(tickZeroEvent()),
            List.of(event("loop")),
            List.of(event("stop"))
        );
        return new EmoteAnimation(
            Identifier.parse("test:event-dispatch"),
            new EmoteMetadata("Event Dispatch", "Event Dispatch"),
            new EmoteAnimation.Settings(true, 0, EmotePlayerBehavior.createDefault(), new EmoteAnimation.PlaybackSettings(loopMode, loopDelayTicks)),
            Map.of(),
            new EmoteAnimation.Timeline(durationTicks, List.of(), events)
        );
    }

    private EmoteAnimation.Event event(String command) {
        return new EmoteAnimation.Event(
            new EmoteAnimation.CommandSource(EmoteAnimation.SourceType.SERVER, null),
            new EmoteAnimation.CommandOrigin(EmoteAnimation.OriginType.ROOT, null, EmoteAnimation.Vec3.ZERO),
            List.of(command)
        );
    }

    private EmoteAnimation.TimelineEvent tickZeroEvent() {
        EmoteAnimation.Event event = event("tick-0");
        return new EmoteAnimation.TimelineEvent(0, event.source(), event.origin(), event.commands());
    }

    private record AnimationFixture(AnimationPlayer player, List<String> executed) {
    }

    private static final class EmptyTimelineTarget implements AnimationPlayer.TimelineTarget {
        @Override
        public Transformation createTransformation(String nodeId, CompiledTimeline.PreparedTransform transform) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void applyTransform(
            String nodeId,
            CompiledTimeline.PreparedTransform transform,
            int interpolationDurationTicks
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setTransformation(String nodeId, Transformation transformation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setVisible(String nodeId, boolean visible) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void resetAll() {
        }
    }
}
