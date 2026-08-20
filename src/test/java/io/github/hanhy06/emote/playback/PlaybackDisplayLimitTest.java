package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.playback.session.PlaybackSession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlaybackDisplayLimitTest {
    @Test
    void rejectsPlaybackOnlyWhenProjectedPartsExceedPositiveLimit() {
        assertFalse(PlaybackEngine.exceedsDisplayEntityLimit(512, 512));
        assertTrue(PlaybackEngine.exceedsDisplayEntityLimit(513, 512));
        assertFalse(PlaybackEngine.exceedsDisplayEntityLimit(10_000, 0));
    }

    @Test
    void replacesCurrentPlaybackPartsBeforeCheckingRequestedParts() {
        assertEquals(500, PlaybackEngine.projectedDisplayEntityCount(480, 80, 100));
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

        assertTrue(PlaybackEngine.shouldStopFor(conditions, PlaybackStopReason.JUMPED));
        assertTrue(PlaybackEngine.shouldStopFor(conditions, PlaybackStopReason.MOUNTED));
        assertTrue(PlaybackEngine.shouldStopFor(conditions, PlaybackStopReason.ATTACKED));
        assertFalse(PlaybackEngine.shouldStopFor(conditions, PlaybackStopReason.DAMAGED));
        assertFalse(PlaybackEngine.shouldStopFor(conditions, PlaybackStopReason.GAME_MODE_CHANGED));
        assertFalse(PlaybackEngine.shouldStopFor(conditions, PlaybackStopReason.MANUAL));
    }

    @Test
    void followsInitiatorViewWhileOfferingAndWaitingForPartner() {
        assertTrue(PlaybackEngine.followsInitiatorView(PlaybackSession.State.SOLO));
        assertTrue(PlaybackEngine.followsInitiatorView(PlaybackSession.State.OFFERING));
        assertTrue(PlaybackEngine.followsInitiatorView(PlaybackSession.State.WAITING));
        assertFalse(PlaybackEngine.followsInitiatorView(PlaybackSession.State.MATCHED));
        assertFalse(PlaybackEngine.followsInitiatorView(PlaybackSession.State.TIMEOUT));
    }
}
