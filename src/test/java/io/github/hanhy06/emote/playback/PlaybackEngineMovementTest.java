package io.github.hanhy06.emote.playback;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaybackEngineMovementTest {
    @Test
    void movementPastConfiguredDistanceStopsImmediately() {
        assertEquals(PlayerPlaybackManager.MovementResult.IMMEDIATE_STOP, PlayerPlaybackManager.movementResult(0.101D * 0.101D, 0.1D));
    }

    @Test
    void movementAtOrBelowConfiguredDistanceKeepsPlaying() {
        assertEquals(PlayerPlaybackManager.MovementResult.NONE, PlayerPlaybackManager.movementResult(0.1D * 0.1D, 0.1D));
        assertEquals(PlayerPlaybackManager.MovementResult.NONE, PlayerPlaybackManager.movementResult(0.09D * 0.09D, 0.1D));
    }

    @Test
    void zeroMovementDistanceDisablesMovementStops() {
        assertEquals(PlayerPlaybackManager.MovementResult.NONE, PlayerPlaybackManager.movementResult(100.0D, 0.0D));
    }
}
