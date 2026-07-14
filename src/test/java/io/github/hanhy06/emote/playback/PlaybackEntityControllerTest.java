package io.github.hanhy06.emote.playback;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackEntityControllerTest {
    @Test
    void cleanupTagsAcceptNamespaceOwnedDisplays() {
        assertTrue(PlaybackEntityController.isCleanupTag("dance", "dance"));
        assertTrue(PlaybackEntityController.isCleanupTag("dance_root", "dance"));
        assertTrue(PlaybackEntityController.isCleanupTag("dance_camera", "dance"));
        assertTrue(PlaybackEntityController.isCleanupTag("dance_12", "dance"));
        assertTrue(PlaybackEntityController.isCleanupTag("dance_p7", "dance"));
    }

    @Test
    void cleanupTagsRejectUnrelatedOrMalformedTags() {
        assertFalse(PlaybackEntityController.isCleanupTag("dance_other", "dance"));
        assertFalse(PlaybackEntityController.isCleanupTag("dance_p", "dance"));
        assertFalse(PlaybackEntityController.isCleanupTag("dance_", "dance"));
        assertFalse(PlaybackEntityController.isCleanupTag("dancer_root", "dance"));
        assertFalse(PlaybackEntityController.isCleanupTag("other_1", "dance"));
    }
}
