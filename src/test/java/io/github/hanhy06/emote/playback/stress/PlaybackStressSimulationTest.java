package io.github.hanhy06.emote.playback.stress;

import com.mojang.math.Transformation;
import io.github.hanhy06.emote.animation.AnimationJsonLoader;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.content.CompiledTimeline;
import io.github.hanhy06.emote.playback.runtime.RootTransform;
import io.github.hanhy06.emote.playback.timeline.TimelinePlayer;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class PlaybackStressSimulationTest {
    private static final String MINECRAFT_VERSION = System.getProperty("emote.minecraftVersion");
    private static final int INSTANCE_COUNT = 100;
    private static final int MIN_START_TICK = 80;
    private static final int MAX_START_TICK = 175;
    private static final long START_TICK_RANDOM_SEED = 0xE607EL;

    @Test
    void keepsOneHundredLateStartingPlayersOnTheSameServerPhase() throws Exception {
        EmoteAnimation animation = new AnimationJsonLoader()
            .load(Path.of("docs/example/emote.dance.json"))
            .animation();
        CompiledTimeline plan = CompiledTimeline.compile(animation);
        int durationTicks = animation.timeline().durationTicks();
        int[] startTicks = randomizedStartTicks();
        List<SimulatedPlayback> activePlaybacks = new ArrayList<>(INSTANCE_COUNT);
        long simulationStartedAt = System.nanoTime();

        for (int serverTick = 0; serverTick <= MAX_START_TICK + durationTicks; serverTick++) {
            for (SimulatedPlayback playback : activePlaybacks) {
                advancePastLoopBoundary(playback.player());
            }

            for (int startTick : startTicks) {
                if (startTick != serverTick) {
                    continue;
                }

                CountingTarget target = new CountingTarget();
                TimelinePlayer player = new TimelinePlayer(plan, target);
                player.startSynchronized(serverTick);
                player.resumeSynchronizedInterpolation();
                activePlaybacks.add(new SimulatedPlayback(player, target));
            }

            int expectedPhase = serverTick % durationTicks;
            for (SimulatedPlayback playback : activePlaybacks) {
                assertEquals(expectedPhase, playback.player().currentTick());
            }
        }

        assertEquals(INSTANCE_COUNT, activePlaybacks.size());
        assertEquals(
            INSTANCE_COUNT * animation.nodes().size(),
            activePlaybacks.stream().mapToInt(playback -> playback.target().snapshotCount).sum()
        );
        assertTrue(activePlaybacks.stream().mapToInt(playback -> playback.target().transformCount).sum() > 0);
        assertTrue(activePlaybacks.stream().allMatch(playback -> playback.target().lastTransformation != null));
        long elapsedNanos = System.nanoTime() - simulationStartedAt;
        int transformCount = activePlaybacks.stream().mapToInt(playback -> playback.target().transformCount).sum();
        System.out.printf(
            "playback-load: instances=%d, startTicks=%d..%d, transforms=%d, elapsedNanos=%d%n",
            INSTANCE_COUNT,
            MIN_START_TICK,
            MAX_START_TICK,
            transformCount,
            elapsedNanos
        );
    }

    private int[] randomizedStartTicks() {
        Random random = new Random(START_TICK_RANDOM_SEED);
        int[] startTicks = new int[INSTANCE_COUNT];
        startTicks[0] = MIN_START_TICK;
        startTicks[1] = MAX_START_TICK;
        for (int index = 2; index < startTicks.length; index++) {
            startTicks[index] = random.nextInt(MIN_START_TICK, MAX_START_TICK + 1);
        }
        return startTicks;
    }

    private void advancePastLoopBoundary(TimelinePlayer player) {
        TimelinePlayer.AdvanceResult result = player.advance();
        if (result == TimelinePlayer.AdvanceResult.LOOP_BOUNDARY) {
            result = player.continueAfterLoopEvent();
        }
        assertNotEquals(TimelinePlayer.AdvanceResult.FINISHED, result);
    }

    private record SimulatedPlayback(TimelinePlayer player, CountingTarget target) {
    }

    private static final class CountingTarget implements TimelinePlayer.TimelineTarget {
        private final RootTransform rootTransform = RootTransform.create(Vec3.ZERO, 0.0F);

        private int transformCount;
        private int snapshotCount;
        private Transformation lastTransformation;

        @Override
        public Transformation createTransformation(String nodeId, CompiledTimeline.PreparedTransform transform) {
            return this.rootTransform.displayTransformation(transform);
        }

        @Override
        public void applyTransform(
            String nodeId,
            CompiledTimeline.PreparedTransform transform,
            int interpolationDurationTicks
        ) {
            this.lastTransformation = this.rootTransform.displayTransformation(transform);
            this.transformCount++;
        }

        @Override
        public void setTransformation(String nodeId, Transformation transformation) {
            this.snapshotCount++;
        }

        @Override
        public void setVisible(String nodeId, boolean visible) {
        }

        @Override
        public void resetAll() {
        }
    }
}
