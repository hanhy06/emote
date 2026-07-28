package io.github.hanhy06.emote.server;

import io.github.hanhy06.emote.config.data.EmoteAccessConfig;
import io.github.hanhy06.emote.emote.PlayResult;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IdleEmoteServiceTest {
    private static final UUID PLAYER_UUID = UUID.fromString("c50d1f70-28d0-4e46-8f8a-334036755c36");
    private static final EmoteAccessConfig.IdleEmote IDLE = new EmoteAccessConfig.IdleEmote(10, "demo:sit");

    @Test
    void playsOnceAfterConfiguredIdleDelay() {
        AtomicLong clock = new AtomicLong(10_000L);
        AtomicInteger playCount = new AtomicInteger();
        IdleEmoteService service = new IdleEmoteService(
            ignoredPlayer -> Optional.of(IDLE),
            (ignoredPlayer, ignoredId) -> {
                playCount.incrementAndGet();
                return PlayResult.SUCCESS;
            },
            ignoredPlayer -> false,
            clock::get
        );

        service.tickPlayer(PLAYER_UUID, 5_000L, null);
        clock.set(15_000L);
        service.tickPlayer(PLAYER_UUID, 5_000L, null);
        service.tickPlayer(PLAYER_UUID, 5_000L, null);

        assertEquals(1, playCount.get());
    }

    @Test
    void activityStartsANewIdleDelay() {
        AtomicLong clock = new AtomicLong(14_999L);
        AtomicInteger playCount = new AtomicInteger();
        IdleEmoteService service = successfulService(clock, playCount);

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
        IdleEmoteService service = new IdleEmoteService(
            ignoredPlayer -> Optional.of(IDLE),
            (ignoredPlayer, ignoredId) -> {
                int attempt = playCount.incrementAndGet();
                return attempt == 1 ? PlayResult.failure("Preparing player skin.") : PlayResult.SUCCESS;
            },
            ignoredPlayer -> false,
            clock::get
        );

        service.tickPlayer(PLAYER_UUID, 5_000L, null);
        clock.set(15_999L);
        service.tickPlayer(PLAYER_UUID, 5_000L, null);
        clock.set(16_000L);
        service.tickPlayer(PLAYER_UUID, 5_000L, null);

        assertEquals(2, playCount.get());
    }

    @Test
    void missingIdleConfigurationClearsPendingState() {
        AtomicLong clock = new AtomicLong(15_000L);
        AtomicInteger playCount = new AtomicInteger();
        IdleEmoteService.IdleEmoteResolver[] resolver = {
            ignoredPlayer -> Optional.of(IDLE)
        };
        IdleEmoteService service = new IdleEmoteService(
            ignoredPlayer -> resolver[0].find(ignoredPlayer),
            (ignoredPlayer, ignoredId) -> {
                playCount.incrementAndGet();
                return PlayResult.SUCCESS;
            },
            ignoredPlayer -> false,
            clock::get
        );

        service.tickPlayer(PLAYER_UUID, 10_000L, null);
        resolver[0] = ignoredPlayer -> Optional.empty();
        service.tickPlayer(PLAYER_UUID, 10_000L, null);
        resolver[0] = ignoredPlayer -> Optional.of(IDLE);
        service.tickPlayer(PLAYER_UUID, 10_000L, null);

        assertEquals(0, playCount.get());
    }

    private IdleEmoteService successfulService(AtomicLong clock, AtomicInteger playCount) {
        return new IdleEmoteService(
            ignoredPlayer -> Optional.of(IDLE),
            (ignoredPlayer, ignoredId) -> {
                playCount.incrementAndGet();
                return PlayResult.SUCCESS;
            },
            ignoredPlayer -> false,
            clock::get
        );
    }
}
