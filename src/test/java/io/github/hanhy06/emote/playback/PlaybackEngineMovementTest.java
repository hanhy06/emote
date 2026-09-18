package io.github.hanhy06.emote.playback;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaybackEngineMovementTest {
    @Test
    void movementPastConfiguredDistanceRequestsOutro() {
        assertEquals(
            PlaybackEngine.MovementResult.REQUEST_OUTRO,
            PlaybackEngine.movementResult(0.11D * 0.11D, 0.1D, false)
        );
    }

    @Test
    void movementWithinThirtyPercentMarginKeepsOutroPlaying() {
        assertEquals(
            PlaybackEngine.MovementResult.NONE,
            PlaybackEngine.movementResult(0.13D * 0.13D, 0.1D, true)
        );
    }

    @Test
    void movementPastThirtyPercentMarginStopsImmediately() {
        assertEquals(
            PlaybackEngine.MovementResult.IMMEDIATE_STOP,
            PlaybackEngine.movementResult(0.131D * 0.131D, 0.1D, true)
        );
    }

    @Test
    void zeroMovementDistanceDisablesMovementStops() {
        assertEquals(
            PlaybackEngine.MovementResult.NONE,
            PlaybackEngine.movementResult(100.0D, 0.0D, false)
        );
    }
}
