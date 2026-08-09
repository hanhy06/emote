package io.github.hanhy06.emote.playback;

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
}
