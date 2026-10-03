package io.github.hanhy06.emote.content;

import io.github.hanhy06.emote.api.sequence.EmoteSequence;

import com.mojang.math.Transformation;
import io.github.hanhy06.emote.api.*;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.playback.PlaybackPlayer;
import io.github.hanhy06.emote.playback.runtime.PlaybackNodes;
import io.github.hanhy06.emote.playback.runtime.RootTransform;
import io.github.hanhy06.emote.playback.session.PlaybackSession;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.hanhy06.emote.api.PlaybackTimeline.Phase.*;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackInspectionTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void exposesActualSequencePositionsAcrossWaitTransitionAndRepeatDelay() {
        PreparedEmote animation = animation(EmoteAnimation.LoopMode.LOOP, 2);
        PreparedEmote compiled = sequence(animation,
            skip(animation),
            new EmoteSequence.WaitStep(2),
            new EmoteSequence.AnimationStep(animation.model().id(), 2, 1),
            new EmoteSequence.WaitStep(3), skip(animation)).compile(firstChoice());
        PlaybackPlayer player = player(compiled);
        player.start();
        player.startEvents();
        PlaybackTimeline timeline = player.timeline();
        assertEquals(List.of(WAIT, ANIMATION, LOOP_DELAY, TRANSITION, ANIMATION, WAIT),
            timeline.segments().stream().map(PlaybackTimeline.Segment::phase).toList());
        assertEquals(List.of(0L, 2L, 4L, 6L, 7L, 9L),
            timeline.segments().stream().map(PlaybackTimeline.Segment::startTick).toList());
        assertEquals(12, timeline.durationTicks());
        assertThrows(UnsupportedOperationException.class, () -> timeline.segments().clear());
        assertPosition(player, WAIT, 0, null, null);
        advance(player, 2);
        PlaybackPosition initial = player.position();
        assertPosition(player, ANIMATION, 0, animation.model().id(), 0);
        assertEquals(2, initial.stepIndex());
        assertEquals(0, initial.repeatIndex());
        advance(player, 2);
        assertPosition(player, LOOP_DELAY, 0, null, null);
        advance(player, 2);
        assertPosition(player, TRANSITION, 0, animation.model().id(), null);
        advance(player, 1);
        assertPosition(player, ANIMATION, 0, animation.model().id(), 0);
        assertEquals(1, player.position().repeatIndex());
        advance(player, 2);
        assertPosition(player, WAIT, 0, null, null);
        advance(player, 3);
        assertPosition(player, WAIT, 3, null, null);
        assertEquals(0, initial.animationTick());
        assertSame(timeline, player.timeline());
    }

    @Test
    void preservesOriginalRepeatNumbersAfterContinueAndBreak() {
        PreparedEmote animation = animation(EmoteAnimation.LoopMode.ONCE, 0);
        PreparedSequence prepared = sequence(animation, new EmoteSequence.AnimationStep(List.of(
            new EmoteSequence.Choice(EmoteSequence.Control.CONTINUE.id(), 0),
            new EmoteSequence.Choice(animation.model().id(), 0),
            new EmoteSequence.Choice(EmoteSequence.Control.BREAK.id(), 0)), 4));
        AtomicInteger choice = new AtomicInteger();
        int[] choices = {0, 1, 2};
        PreparedEmote compiled = prepared.compile(new Random() {
            @Override public int nextInt(int bound) { return choices[choice.getAndIncrement()]; }
        });
        var segment = compiled.playbackTimeline().segments().getFirst();
        assertEquals(1, segment.repeatIndex());
        assertEquals(0, segment.stepIndex());
        assertEquals(animation.model().id(), segment.animationId());
        assertEquals(2, compiled.playbackTimeline().durationTicks());
        assertEquals(3, choice.get());
    }

    @Test
    void identifiesWaitOnlyAndEmptySelectionsWithoutReportingAnAnimation() {
        PreparedEmote animation = animation(EmoteAnimation.LoopMode.ONCE, 0);
        EmoteSequence.AnimationStep skip = skip(animation);
        PlaybackPlayer waits = player(sequence(animation, skip, new EmoteSequence.WaitStep(2), skip,
            new EmoteSequence.WaitStep(3), skip).compile(firstChoice()));
        waits.start();
        assertEquals(1, waits.position().stepIndex());
        advance(waits, 2);
        assertEquals(3, waits.position().stepIndex());
        assertPosition(waits, WAIT, 0, null, null);
        PlaybackPlayer empty = player(sequence(animation, skip).compile(firstChoice()));
        empty.start();
        assertPosition(empty, WAIT, 0, null, null);
        assertEquals(1, empty.timeline().durationTicks());
    }

    @Test
    void reportsStandaloneLoopDelayAndRewindWithoutChangingSnapshots() {
        PlaybackPlayer player = player(animation(EmoteAnimation.LoopMode.LOOP, 3));
        player.start();
        player.startEvents();
        advance(player, 2);
        PlaybackPosition delayStart = player.position();
        assertPosition(player, LOOP_DELAY, 0, null, null);
        advance(player, 1);
        assertPosition(player, LOOP_DELAY, 1, null, null);
        advance(player, 2);
        assertEquals(ANIMATION, player.position().phase());
        assertEquals(0, player.position().animationTick());
        assertEquals(0, player.currentTick());
        assertNull(player.position().stepIndex());
        assertEquals(0, delayStart.phaseTick());
    }

    @Test
    void reportsSynchronizedPhaseAndPreservesItOnStop() {
        PlaybackPlayer player = player(animation(EmoteAnimation.LoopMode.SERVER_SYNC, 3));
        player.startSynchronized(3);
        assertPosition(player, LOOP_DELAY, 1, null, null);
        assertEquals(2, player.currentTick());
        var position = player.position();
        player.stop();
        assertEquals(position, player.position());
    }

    @Test
    void reportsHoldDurationAndAnUnboundedTimelineSegment() {
        PlaybackPlayer player = player(animation(EmoteAnimation.LoopMode.HOLD, 0));
        player.start();
        advance(player, 2);
        assertEquals(HOLD, player.position().phase());
        assertEquals(0, player.position().phaseTick());
        assertNull(player.position().animationTick());
        assertNull(player.timeline().segments().getLast().endTick());
        advance(player, 3);
        assertEquals(3, player.position().phaseTick());
        var position = player.position();
        player.stop();
        assertEquals(position, player.position());
    }

    @Test
    void playbackInfoSeparatesSequenceAndAnimationIdsAndElapsedTicks() {
        PreparedEmote animation = animation(EmoteAnimation.LoopMode.ONCE, 0);
        PlaybackPlayer player = player(sequence(animation, skip(animation), new EmoteSequence.WaitStep(1),
            new EmoteSequence.AnimationStep(animation.model().id(), 1)).compile(firstChoice()));
        player.start();
        UUID actorId = UUID.randomUUID();
        PlaybackSession session = new PlaybackSession(UUID.randomUUID(), Level.OVERWORLD, "test:sequence",
            new PlaybackNodes(RootTransform.create(Vec3.ZERO, 0), Map.of()), player, Map.of());
        session.bindCallbacks(List.of(), Map.of(), 100);
        session.startPlayback();
        session.tick(101);
        player.advance();
        PlaybackInfo info = session.playbackInfo(actorId);
        assertEquals(Identifier.parse("test:sequence"), info.emoteId());
        assertEquals(animation.model().id(), info.position().animationId());
        assertEquals(1, info.elapsedTicks());
        assertEquals(1, info.timelineTick());
        assertEquals(0, info.position().animationTick());
    }

    @Test
    void endingTickCallbacksStillSeeTheirOwnAnimation() {
        PreparedEmote animation = animation(EmoteAnimation.LoopMode.ONCE, 0);
        PlaybackPlayer player = player(sequence(animation, new EmoteSequence.AnimationStep(animation.model().id(), 2))
            .compile(new Random(0)));
        player.bindLifecycleListener(new PlaybackPlayer.LifecycleListener() {
            @Override public void onTick(int tick) {
                assertEquals(tick, player.position().animationTick());
                assertEquals(ANIMATION, player.position().phase());
            }
        });
        player.start();
        player.startEvents();
        advance(player, 2);
        assertEquals(1, player.position().repeatIndex());
        assertEquals(0, player.position().animationTick());
        advance(player, 2);
        assertEquals(2, player.position().animationTick());
    }

    private static void assertPosition(PlaybackPlayer player, PlaybackTimeline.Phase phase, long tick,
                                       Identifier animationId, Integer animationTick) {
        PlaybackPosition position = player.position();
        assertEquals(phase, position.phase());
        assertEquals(tick, position.phaseTick());
        assertEquals(animationId, position.animationId());
        assertEquals(animationTick, position.animationTick());
    }

    private static void advance(PlaybackPlayer player, int ticks) {
        for (int tick = 0; tick < ticks; tick++) player.advance();
    }

    private static Random firstChoice() {
        return new Random() { @Override public int nextInt(int bound) { return 0; } };
    }

    private static EmoteSequence.AnimationStep skip(PreparedEmote animation) {
        return new EmoteSequence.AnimationStep(List.of(
            new EmoteSequence.Choice(EmoteSequence.Control.CONTINUE.id(), 0),
            new EmoteSequence.Choice(animation.model().id(), 0)), 1);
    }

    private static PreparedSequence sequence(PreparedEmote animation, EmoteSequence.Step... steps) {
        return PreparedSequence.resolve(new EmoteSequence(Identifier.parse("test:sequence"),
            new EmoteMetadata("Sequence", "test"), new EmoteSequence.Settings(0, EmotePlayerBehavior.createDefault()),
            List.of(steps)), Map.of(animation.id(), animation));
    }

    private static PreparedEmote animation(EmoteAnimation.LoopMode mode, int delay) {
        EmoteAnimation animation = new EmoteAnimation(Identifier.parse("test:animation"), new EmoteMetadata("Animation", "test"),
            new EmoteAnimation.Settings(true, 0, 50, 1, EmotePlayerBehavior.createDefault(),
                new EmoteAnimation.PlaybackSettings(mode, 0, delay)), EmoteAnimation.MolangPrograms.empty(),
            Map.of("root", new EmoteAnimation.AnchorNode(null, EmoteAnimation.LocalTransform.IDENTITY)),
            new EmoteAnimation.Timeline(2, Map.of(), EmoteAnimation.Events.empty()), List.of());
        return PreparedEmote.from(new LoadedAnimation(Path.of("animation.json"), "test", animation));
    }

    private static PlaybackPlayer player(PreparedEmote animation) {
        return new PlaybackPlayer(animation, new PlaybackPlayer.TimelineTarget() {
            public Transformation createTransformation(String nodeId, PreparedEmote.PreparedTransform transform) {
                return new Transformation(transform.localMatrix());
            }
            public void applyTransform(String nodeId, PreparedEmote.PreparedTransform transform, int duration) {}
            public void setVisible(String nodeId, boolean visible) {}
            public void applyNbt(String nodeId, CompoundTag nbt) {}
            public void resetAll() {}
        });
    }
}
