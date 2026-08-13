package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackDisplayLimitTest {
    @Test
    void rejectsPlaybackOnlyWhenProjectedPartsExceedPositiveLimit() {
        assertFalse(PlaybackManager.exceedsDisplayEntityLimit(512, 512));
        assertTrue(PlaybackManager.exceedsDisplayEntityLimit(513, 512));
        assertFalse(PlaybackManager.exceedsDisplayEntityLimit(10_000, 0));
    }

    @Test
    void replacesCurrentPlaybackPartsBeforeCheckingRequestedParts() {
        assertEquals(500, PlaybackManager.projectedDisplayEntityCount(480, 80, 100));
    }

    @Test
    void appliesOnlyEnabledEventDrivenStopConditions() {
        EmotePlayerBehavior.StopConditions conditions = new EmotePlayerBehavior.StopConditions(
            0.1D,
            true,
            false,
            true,
            false,
            true,
            false
        );

        assertTrue(PlaybackManager.shouldStopFor(conditions, PlaybackStopReason.JUMPED));
        assertTrue(PlaybackManager.shouldStopFor(conditions, PlaybackStopReason.MOUNTED));
        assertTrue(PlaybackManager.shouldStopFor(conditions, PlaybackStopReason.ATTACKED));
        assertFalse(PlaybackManager.shouldStopFor(conditions, PlaybackStopReason.DAMAGED));
        assertFalse(PlaybackManager.shouldStopFor(conditions, PlaybackStopReason.GAME_MODE_CHANGED));
        assertFalse(PlaybackManager.shouldStopFor(conditions, PlaybackStopReason.MANUAL));
    }
}
