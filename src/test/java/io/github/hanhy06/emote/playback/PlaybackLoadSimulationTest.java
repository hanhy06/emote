package io.github.hanhy06.emote.playback;

import com.mojang.math.Transformation;
import io.github.hanhy06.emote.animation.EmoteAnimationJsonLoader;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackLoadSimulationTest {
    private static final String MINECRAFT_VERSION = System.getProperty("emote.minecraftVersion");
    private static final int INSTANCE_COUNT = 50;
    private static final int START_WINDOW_TICKS = 5;
    private static final long START_TICK_RANDOM_SEED = 0xE607EL;

    @Test
    void keepsFiftyStaggeredPlayersOnTheSameServerPhase() throws Exception {
        EmoteAnimation animation = new EmoteAnimationJsonLoader()
            .load(Path.of("docs/example/emote.dance.json"), MINECRAFT_VERSION)
            .animation();
        PlaybackPlan plan = PlaybackPlan.compile(animation);
        int durationTicks = animation.timeline().durationTicks();
        int[] startTicks = randomizedStartTicks();
        List<SimulatedPlayback> activePlaybacks = new ArrayList<>(INSTANCE_COUNT);

        for (int serverTick = 0; serverTick < durationTicks + START_WINDOW_TICKS; serverTick++) {
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
    }

    private int[] randomizedStartTicks() {
        Random random = new Random(START_TICK_RANDOM_SEED);
        int[] startTicks = new int[INSTANCE_COUNT];
        for (int index = 0; index < START_WINDOW_TICKS; index++) {
            startTicks[index] = index;
        }
        for (int index = START_WINDOW_TICKS; index < startTicks.length; index++) {
            startTicks[index] = random.nextInt(START_WINDOW_TICKS);
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
        private final EmoteRootTransform rootTransform = EmoteRootTransform.create(Vec3.ZERO, 0.0F);
        private int transformCount;
        private int snapshotCount;

        @Override
        public Transformation createTransformation(PlaybackPlan.PreparedTransform transform) {
            return this.rootTransform.displayTransformation(transform);
        }

        @Override
        public void applyTransform(
            String nodeId,
            PlaybackPlan.PreparedTransform transform,
            int interpolationDurationTicks
        ) {
            this.rootTransform.displayTransformation(transform);
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
