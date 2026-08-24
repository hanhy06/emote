package io.github.hanhy06.emote.playback;

import com.mojang.math.Transformation;
import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.content.LoadedAnimation;
import io.github.hanhy06.emote.content.PreparedAnimation;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
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

    @Test
    void repeatsNamedTimelineCallbacksAfterLoopRestart() {
        EmoteAnimation.Callback callback = new EmoteAnimation.Callback(Identifier.parse("test:swing"), "right_hand");
        EmoteAnimation.TimelineEvent event = new EmoteAnimation.TimelineEvent(
            0,
            new EmoteAnimation.CommandSource(EmoteAnimation.SourceType.SERVER, null),
            new EmoteAnimation.CommandOrigin(EmoteAnimation.OriginType.ROOT, null, EmoteAnimation.Vec3.ZERO),
            List.of(),
            List.of(callback)
        );
        EmoteAnimation animation = animation(1, EmoteAnimation.LoopMode.LOOP, 0);
        animation = new EmoteAnimation(
            animation.id(),
            animation.metadata(),
            animation.settings(),
            animation.molang(),
            animation.nodes(),
            new EmoteAnimation.Timeline(1, Map.of(), new EmoteAnimation.Events(List.of(), List.of(event), List.of(), List.of()))
        );
        PreparedAnimation emote = PreparedAnimation.from(new LoadedAnimation(Path.of("callback-test.json"), "test", animation));
        AnimationPlayer player = new AnimationPlayer(emote, new EmptyTimelineTarget());
        List<EmoteAnimation.Callback> executed = new ArrayList<>();
        player.bindEvents(dispatched -> executed.addAll(dispatched.callbacks()));

        player.start();
        player.startEvents();
        player.advance();

        assertEquals(List.of(callback, callback), executed);
    }

    private AnimationFixture fixture(int durationTicks, EmoteAnimation.LoopMode loopMode, int loopDelayTicks) {
        List<String> executed = new ArrayList<>();
        EmoteAnimation animation = animation(durationTicks, loopMode, loopDelayTicks);
        PreparedAnimation emote = PreparedAnimation.from(new LoadedAnimation(Path.of("event-test.json"), "test", animation));
        AnimationPlayer player = new AnimationPlayer(emote, new EmptyTimelineTarget());
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
            new EmoteAnimation.Settings(true, 0, 50.0F, EmotePlayerBehavior.createDefault(), new EmoteAnimation.PlaybackSettings(loopMode, loopDelayTicks)),
            EmoteAnimation.MolangPrograms.empty(),
            Map.of(),
            new EmoteAnimation.Timeline(durationTicks, Map.of(), events)
        );
    }

    private EmoteAnimation.Event event(String command) {
        return new EmoteAnimation.Event(
            new EmoteAnimation.CommandSource(EmoteAnimation.SourceType.SERVER, null),
            new EmoteAnimation.CommandOrigin(EmoteAnimation.OriginType.ROOT, null, EmoteAnimation.Vec3.ZERO),
            List.of(command),
            List.of()
        );
    }

    private EmoteAnimation.TimelineEvent tickZeroEvent() {
        EmoteAnimation.Event event = event("tick-0");
        return new EmoteAnimation.TimelineEvent(0, event.source(), event.origin(), event.commands(), event.callbacks());
    }

    private record AnimationFixture(AnimationPlayer player, List<String> executed) {
    }

    private static final class EmptyTimelineTarget implements AnimationPlayer.TimelineTarget {
        @Override
        public Transformation createTransformation(String nodeId, PreparedAnimation.PreparedTransform transform) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void applyTransform(
            String nodeId,
            PreparedAnimation.PreparedTransform transform,
            int interpolationDurationTicks
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setVisible(String nodeId, boolean visible) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void applyNbt(String nodeId, net.minecraft.nbt.CompoundTag nbt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void resetAll() {
        }
    }
}
