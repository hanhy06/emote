package io.github.hanhy06.emote.playback;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackLoadTestTest {
    @Test
    void laysOutOneHundredInstancesInAOneBlockSpacedGrid() {
        Vec3 origin = new Vec3(100.25D, 64.0D, 200.75D);

        int instanceCount = PlaybackLoadTest.DEFAULT_INSTANCE_COUNT;
        assertEquals(10, PlaybackLoadTest.gridSize(instanceCount));
        assertEquals(origin.add(-4.5D, 0.0D, -4.5D), PlaybackLoadTest.gridPosition(origin, 0, instanceCount));
        assertEquals(origin.add(4.5D, 0.0D, -4.5D), PlaybackLoadTest.gridPosition(origin, 9, instanceCount));
        assertEquals(origin.add(-4.5D, 0.0D, -3.5D), PlaybackLoadTest.gridPosition(origin, 10, instanceCount));
        assertEquals(origin.add(4.5D, 0.0D, 4.5D), PlaybackLoadTest.gridPosition(origin, 99, instanceCount));
    }

    @Test
    void expandsTheGridForARequestedCustomInstanceCount() {
        assertEquals(11, PlaybackLoadTest.gridSize(101));
        assertEquals(16, PlaybackLoadTest.gridSize(250));
    }

    @Test
    void mixesEveryAvailableEmoteBeforeStartingAnotherDeck() {
        List<String> selection = PlaybackLoadTest.createRandomizedSelection(
            List.of("first", "second", "third"),
            new Random(1234L),
            PlaybackLoadTest.DEFAULT_INSTANCE_COUNT
        );

        assertEquals(PlaybackLoadTest.DEFAULT_INSTANCE_COUNT, selection.size());
        for (String id : List.of("first", "second", "third")) {
            long count = selection.stream().filter(id::equals).count();
            assertTrue(count == 33L || count == 34L);
        }
    }

    @Test
    void distributesInitialTicksAcrossTheLatePlaybackWindow() {
        Random random = new Random(5678L);

        assertEquals(PlaybackLoadTest.MIN_INITIAL_TICK, PlaybackLoadTest.initialTick(random, 0));
        assertEquals(PlaybackLoadTest.MAX_INITIAL_TICK, PlaybackLoadTest.initialTick(random, 1));
        for (int index = 2; index < PlaybackLoadTest.DEFAULT_INSTANCE_COUNT; index++) {
            int tick = PlaybackLoadTest.initialTick(random, index);
            assertTrue(tick >= PlaybackLoadTest.MIN_INITIAL_TICK);
            assertTrue(tick <= PlaybackLoadTest.MAX_INITIAL_TICK);
        }
    }

    @Test
    void estimatesTpsFromMeasuredServerTickDuration() {
        assertEquals(20.0D, PlaybackLoadTest.estimatedTps(10.0D, 20.0D), 0.0001D);
        assertEquals(20.0D, PlaybackLoadTest.estimatedTps(50.0D, 20.0D), 0.0001D);
        assertEquals(10.0D, PlaybackLoadTest.estimatedTps(100.0D, 20.0D), 0.0001D);
    }
}
