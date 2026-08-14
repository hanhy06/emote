package io.github.hanhy06.emote.server;

import io.github.hanhy06.emote.api.PlayResult;
import io.github.hanhy06.emote.config.AccessConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdlePlaybackServiceTest {
    private static final UUID PLAYER_UUID = UUID.fromString("c50d1f70-28d0-4e46-8f8a-334036755c36");
    private static final AccessConfig.IdleSettings IDLE = new AccessConfig.IdleSettings(
        200,
        List.of("demo:sit")
    );

    @Test
    void checksPlayersOnceEveryTwentyTicks() {
        IdlePlaybackService service = successfulService(new AtomicLong(), new AtomicInteger());

        assertTrue(service.advanceCheckSchedule());
        for (int tick = 1; tick < IdlePlaybackService.CHECK_INTERVAL_TICKS; tick++) {
            assertFalse(service.advanceCheckSchedule());
        }
        assertTrue(service.advanceCheckSchedule());
    }

    @Test
    void playsAtEveryConfiguredIdleInterval() {
        AtomicLong clock = new AtomicLong(10_000L);
        AtomicInteger playCount = new AtomicInteger();
        IdlePlaybackService service = new IdlePlaybackService(
            ignoredPlayer -> Optional.of(IDLE),
            (ignoredPlayer, ignoredId) -> {
                playCount.incrementAndGet();
                return PlayResult.SUCCESS;
            },
            ignoredPlayer -> false,
            clock::get,
            RandomGenerator.getDefault()
        );

        service.tickPlayer(PLAYER_UUID, 5_000L, null);
        clock.set(15_000L);
        service.tickPlayer(PLAYER_UUID, 5_000L, null);
        service.tickPlayer(PLAYER_UUID, 5_000L, null);
        clock.set(24_999L);
        service.tickPlayer(PLAYER_UUID, 5_000L, null);
        clock.set(25_000L);
        service.tickPlayer(PLAYER_UUID, 5_000L, null);
        clock.set(35_000L);
        service.tickPlayer(PLAYER_UUID, 5_000L, null);

        assertEquals(3, playCount.get());
    }

    @Test
    void activityStartsANewIdleDelay() {
        AtomicLong clock = new AtomicLong(14_999L);
        AtomicInteger playCount = new AtomicInteger();
        IdlePlaybackService service = successfulService(clock, playCount);

        service.tickPlayer(PLAYER_UUID, 5_000L, null);
        service.tickPlayer(PLAYER_UUID, 14_000L, null);
        clock.set(23_999L);
        service.tickPlayer(PLAYER_UUID, 14_000L, null);
        clock.set(24_000L);
        service.tickPlayer(PLAYER_UUID, 14_000L, null);

        assertEquals(1, playCount.get());
    }

    @Test
    void retriesFailedPlaybackAtOneSecondIntervals() {
        AtomicLong clock = new AtomicLong(15_000L);
        AtomicInteger playCount = new AtomicInteger();
        IdlePlaybackService service = new IdlePlaybackService(
            ignoredPlayer -> Optional.of(IDLE),
            (ignoredPlayer, ignoredId) -> {
                int attempt = playCount.incrementAndGet();
                return attempt == 1 ? PlayResult.failure("Preparing player skin.") : PlayResult.SUCCESS;
            },
            ignoredPlayer -> false,
            clock::get,
            RandomGenerator.getDefault()
        );

        service.tickPlayer(PLAYER_UUID, 5_000L, null);
        clock.set(15_999L);
        service.tickPlayer(PLAYER_UUID, 5_000L, null);
        clock.set(16_000L);
        service.tickPlayer(PLAYER_UUID, 5_000L, null);

        assertEquals(2, playCount.get());
    }

    @Test
    void avoidsRepeatingTheLastSuccessfulEmote() {
        AccessConfig.IdleSettings randomIdle = new AccessConfig.IdleSettings(
            200,
            List.of("demo:sit", "demo:sleep")
        );
        AtomicLong clock = new AtomicLong(15_000L);
        List<String> playedIds = new ArrayList<>();
        IdlePlaybackService service = new IdlePlaybackService(
            ignoredPlayer -> Optional.of(randomIdle),
            (ignoredPlayer, id) -> {
                playedIds.add(id);
                return PlayResult.SUCCESS;
            },
            ignoredPlayer -> false,
            clock::get,
            RandomGenerator.getDefault()
        );

        service.tickPlayer(PLAYER_UUID, 5_000L, null);
        clock.set(25_000L);
        service.tickPlayer(PLAYER_UUID, 5_000L, null);

        assertEquals(2, playedIds.size());
        assertNotEquals(playedIds.getFirst(), playedIds.getLast());
    }

    @Test
    void usesWeightedChancesAfterExcludingTheLastSuccessfulEmote() {
        AccessConfig.IdleSettings weightedIdle = new AccessConfig.IdleSettings(
            200,
            List.of(
                new AccessConfig.IdleSettings.Choice("demo:first", 10),
                new AccessConfig.IdleSettings.Choice("demo:second", 20),
                new AccessConfig.IdleSettings.Choice("demo:third", 70)
            )
        );
        AtomicLong clock = new AtomicLong(15_000L);
        List<String> playedIds = new ArrayList<>();
        int[] randomValues = {15, 0, 89, 0};
        AtomicInteger randomIndex = new AtomicInteger();
        Random random = new Random() {
            @Override
            public int nextInt(int bound) {
                return randomValues[randomIndex.getAndIncrement()];
            }
        };
        IdlePlaybackService service = new IdlePlaybackService(
            ignoredPlayer -> Optional.of(weightedIdle),
            (ignoredPlayer, id) -> {
                playedIds.add(id);
                return PlayResult.SUCCESS;
            },
            ignoredPlayer -> false,
            clock::get,
            random
        );

        service.tickPlayer(PLAYER_UUID, 5_000L, null);
        clock.set(25_000L);
        service.tickPlayer(PLAYER_UUID, 5_000L, null);
        clock.set(35_000L);
        service.tickPlayer(PLAYER_UUID, 5_000L, null);

        assertEquals(List.of("demo:second", "demo:first", "demo:third"), playedIds);
    }

    @Test
    void missingIdleConfigurationClearsPendingState() {
        AtomicLong clock = new AtomicLong(15_000L);
        AtomicInteger playCount = new AtomicInteger();
        IdlePlaybackService.IdleSettingsResolver[] resolver = {
            ignoredPlayer -> Optional.of(IDLE)
        };
        IdlePlaybackService service = new IdlePlaybackService(
            ignoredPlayer -> resolver[0].find(ignoredPlayer),
            (ignoredPlayer, ignoredId) -> {
                playCount.incrementAndGet();
                return PlayResult.SUCCESS;
            },
            ignoredPlayer -> false,
            clock::get,
            RandomGenerator.getDefault()
        );

        service.tickPlayer(PLAYER_UUID, 10_000L, null);
        resolver[0] = ignoredPlayer -> Optional.empty();
        service.onAccessConfigReload(AccessConfig.createDefault());
        service.tickPlayer(PLAYER_UUID, 10_000L, null);
        resolver[0] = ignoredPlayer -> Optional.of(IDLE);
        service.onAccessConfigReload(AccessConfig.createDefault());
        service.tickPlayer(PLAYER_UUID, 10_000L, null);

        assertEquals(0, playCount.get());
    }

    @Test
    void cachesIdlePermissionResolutionForOneSecond() {
        AtomicLong clock = new AtomicLong(10_000L);
        AtomicInteger resolveCount = new AtomicInteger();
        IdlePlaybackService service = new IdlePlaybackService(
            ignoredPlayer -> {
                resolveCount.incrementAndGet();
                return Optional.of(IDLE);
            },
            (ignoredPlayer, ignoredId) -> PlayResult.SUCCESS,
            ignoredPlayer -> false,
            clock::get,
            RandomGenerator.getDefault()
        );

        service.tickPlayer(PLAYER_UUID, 5_000L, null);
        clock.set(10_999L);
        service.tickPlayer(PLAYER_UUID, 5_000L, null);
        clock.set(11_000L);
        service.tickPlayer(PLAYER_UUID, 5_000L, null);

        assertEquals(2, resolveCount.get());
    }

    private IdlePlaybackService successfulService(AtomicLong clock, AtomicInteger playCount) {
        return new IdlePlaybackService(
            ignoredPlayer -> Optional.of(IDLE),
            (ignoredPlayer, ignoredId) -> {
                playCount.incrementAndGet();
                return PlayResult.SUCCESS;
            },
            ignoredPlayer -> false,
            clock::get,
            RandomGenerator.getDefault()
        );
    }
}
