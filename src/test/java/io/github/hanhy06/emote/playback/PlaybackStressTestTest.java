package io.github.hanhy06.emote.playback;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackStressTestTest {
    @Test
    void laysOutOneHundredInstancesInAOneBlockSpacedGrid() {
        Vec3 origin = new Vec3(100.25D, 64.0D, 200.75D);

        int instanceCount = PlaybackStressTest.DEFAULT_INSTANCE_COUNT;
        assertEquals(10, PlaybackStressTest.gridSize(instanceCount));
        assertEquals(origin.add(-4.5D, 0.0D, -4.5D), PlaybackStressTest.gridPosition(origin, 0, instanceCount));
        assertEquals(origin.add(4.5D, 0.0D, -4.5D), PlaybackStressTest.gridPosition(origin, 9, instanceCount));
        assertEquals(origin.add(-4.5D, 0.0D, -3.5D), PlaybackStressTest.gridPosition(origin, 10, instanceCount));
        assertEquals(origin.add(4.5D, 0.0D, 4.5D), PlaybackStressTest.gridPosition(origin, 99, instanceCount));
    }

    @Test
    void expandsTheGridForARequestedCustomInstanceCount() {
        assertEquals(11, PlaybackStressTest.gridSize(101));
        assertEquals(16, PlaybackStressTest.gridSize(250));
    }

    @Test
    void mixesEveryAvailableEmoteBeforeStartingAnotherDeck() {
        List<String> selection = PlaybackStressTest.createRandomizedSelection(
            List.of("first", "second", "third"),
            new Random(1234L),
            PlaybackStressTest.DEFAULT_INSTANCE_COUNT
        );

        assertEquals(PlaybackStressTest.DEFAULT_INSTANCE_COUNT, selection.size());
        for (String id : List.of("first", "second", "third")) {
            long count = selection.stream().filter(id::equals).count();
            assertTrue(count == 33L || count == 34L);
        }
    }

    @Test
    void distributesInitialTicksAcrossTheLatePlaybackWindow() {
        Random random = new Random(5678L);

        assertEquals(PlaybackStressTest.MIN_INITIAL_TICK, PlaybackStressTest.initialTick(random, 0));
        assertEquals(PlaybackStressTest.MAX_INITIAL_TICK, PlaybackStressTest.initialTick(random, 1));
        for (int index = 2; index < PlaybackStressTest.DEFAULT_INSTANCE_COUNT; index++) {
            int tick = PlaybackStressTest.initialTick(random, index);
            assertTrue(tick >= PlaybackStressTest.MIN_INITIAL_TICK);
            assertTrue(tick <= PlaybackStressTest.MAX_INITIAL_TICK);
        }
    }

    @Test
    void estimatesTpsFromMeasuredServerTickDuration() {
        assertEquals(20.0D, PlaybackStressTest.estimatedTps(10.0D, 20.0D), 0.0001D);
        assertEquals(20.0D, PlaybackStressTest.estimatedTps(50.0D, 20.0D), 0.0001D);
        assertEquals(10.0D, PlaybackStressTest.estimatedTps(100.0D, 20.0D), 0.0001D);
    }
}
