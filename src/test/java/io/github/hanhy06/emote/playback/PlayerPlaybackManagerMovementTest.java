package io.github.hanhy06.emote.playback;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerPlaybackManagerMovementTest {
    @Test
    void movementPastConfiguredDistanceStopsImmediately() {
        assertTrue(PlayerPlaybackManager.shouldStopForMovement(0.101D * 0.101D, 0.1D));
    }

    @Test
    void movementAtOrBelowConfiguredDistanceKeepsPlaying() {
        assertFalse(PlayerPlaybackManager.shouldStopForMovement(0.1D * 0.1D, 0.1D));
        assertFalse(PlayerPlaybackManager.shouldStopForMovement(0.09D * 0.09D, 0.1D));
    }

    @Test
    void zeroMovementDistanceDisablesMovementStops() {
        assertFalse(PlayerPlaybackManager.shouldStopForMovement(100.0D, 0.0D));
    }
}
