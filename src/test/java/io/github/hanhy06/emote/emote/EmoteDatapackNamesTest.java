package io.github.hanhy06.emote.emote;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmoteDatapackNamesTest {
    @Test
    void createsFunctionIdsAndEntityTags() {
        assertEquals("dance:_/create", EmoteDatapackNames.createFunctionId("dance"));
        assertEquals("dance:a/default/play", EmoteDatapackNames.entrypointFunctionId("dance", "a/default/play"));
        assertEquals("dance:_/stop_anim", EmoteDatapackNames.stopAnimationFunctionId("dance"));
        assertEquals("dance:_/delete", EmoteDatapackNames.deleteFunctionId("dance"));
        assertEquals("dance_root", EmoteDatapackNames.rootTag("dance"));
        assertEquals("dance_camera", EmoteDatapackNames.cameraTag("dance"));
        assertEquals("dance_12", EmoteDatapackNames.partTag("dance", 12));
        assertTrue(EmoteDatapackNames.isAnimationTag("animation_walk"));
        assertFalse(EmoteDatapackNames.isAnimationTag("dance_animation_walk"));
    }

    @Test
    void cleanupTagsAcceptNamespaceOwnedDisplays() {
        assertTrue(EmoteDatapackNames.isCleanupTag("dance", "dance"));
        assertTrue(EmoteDatapackNames.isCleanupTag("dance_root", "dance"));
        assertTrue(EmoteDatapackNames.isCleanupTag("dance_camera", "dance"));
        assertTrue(EmoteDatapackNames.isCleanupTag("dance_12", "dance"));
        assertTrue(EmoteDatapackNames.isCleanupTag("dance_p7", "dance"));
    }

    @Test
    void cleanupTagsRejectUnrelatedOrMalformedTags() {
        assertFalse(EmoteDatapackNames.isCleanupTag("dance_other", "dance"));
        assertFalse(EmoteDatapackNames.isCleanupTag("dance_p", "dance"));
        assertFalse(EmoteDatapackNames.isCleanupTag("dance_", "dance"));
        assertFalse(EmoteDatapackNames.isCleanupTag("dancer_root", "dance"));
        assertFalse(EmoteDatapackNames.isCleanupTag("other_1", "dance"));
    }
}
