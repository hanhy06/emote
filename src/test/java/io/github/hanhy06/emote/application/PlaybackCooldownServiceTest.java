package io.github.hanhy06.emote.application;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaybackCooldownServiceTest {
    private static final UUID PLAYER_ID = UUID.fromString("4e20ffac-0bc9-4ca1-ad81-d1f1b29e4679");

    @Test
    void claimedCooldownBlocksUseUntilPlaybackEndsAndDurationExpires() {
        AtomicLong tick = new AtomicLong();
        PlaybackCooldownService service = service(tick);
        PlaybackCooldownService.Reservation reservation = service.reservation(null, "demo:wave", 20);

        assertEquals(PlaybackCooldownService.State.AVAILABLE, service.status(null, "demo:wave").state());
        service.claim(reservation);
        assertEquals(PlaybackCooldownService.State.IN_USE, service.status(null, "demo:wave").state());

        tick.set(10L);
        service.onPlaybackEnded(null, "demo:wave");
        PlaybackCooldownService.Status coolingDown = service.status(null, "demo:wave");
        assertEquals(PlaybackCooldownService.State.COOLING_DOWN, coolingDown.state());
        assertEquals(20L, coolingDown.remainingTicks());

        tick.set(29L);
        assertEquals(1L, service.status(null, "demo:wave").remainingTicks());
        tick.set(30L);
        assertEquals(PlaybackCooldownService.State.AVAILABLE, service.status(null, "demo:wave").state());
    }

    @Test
    void failedPlaybackAndReleasedPartnerReservationRemoveInUseState() {
        PlaybackCooldownService service = service(new AtomicLong());
        PlaybackCooldownService.Reservation failed = service.reservation(null, "demo:failed", 20);
        PlaybackCooldownService.Reservation partner = service.reservation(null, "demo:partner", 40);

        service.claim(failed);
        service.release(failed);
        assertEquals(PlaybackCooldownService.State.AVAILABLE, service.status(null, "demo:failed").state());

        service.claim(partner);
        service.onReservationReleased(PLAYER_ID, "demo:partner");
        assertEquals(PlaybackCooldownService.State.AVAILABLE, service.status(null, "demo:partner").state());
    }

    @Test
    void differentEmoteStatesArePreservedIndependently() {
        AtomicLong tick = new AtomicLong(5L);
        PlaybackCooldownService service = service(tick);
        PlaybackCooldownService.Reservation wave = service.reservation(null, "demo:wave", 20);
        PlaybackCooldownService.Reservation dance = service.reservation(null, "demo:dance", 40);

        service.claim(wave);
        service.claim(dance);
        service.onPlaybackEnded(null, "demo:wave");

        assertEquals(PlaybackCooldownService.State.COOLING_DOWN, service.status(null, "demo:wave").state());
        assertEquals(PlaybackCooldownService.State.IN_USE, service.status(null, "demo:dance").state());

        service.clear();
        assertEquals(PlaybackCooldownService.State.AVAILABLE, service.status(null, "demo:wave").state());
        assertEquals(PlaybackCooldownService.State.AVAILABLE, service.status(null, "demo:dance").state());
    }

    private static PlaybackCooldownService service(AtomicLong tick) {
        return new PlaybackCooldownService(ignoredPlayer -> PLAYER_ID, ignoredPlayer -> tick.get());
    }
}
