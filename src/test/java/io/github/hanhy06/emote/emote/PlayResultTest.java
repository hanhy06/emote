package io.github.hanhy06.emote.emote;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayResultTest {
    @Test
    void successHasNoErrorMessage() {
        assertTrue(PlayResult.SUCCESS.isSuccess());
    }

    @Test
    void failureTrimsErrorMessage() {
        PlayResult result = PlayResult.failure(" Animation unavailable. ");

        assertFalse(result.isSuccess());
        assertEquals("Animation unavailable.", result.errorMessage());
    }

    @Test
    void failureRejectsBlankErrorMessage() {
        assertThrows(IllegalArgumentException.class, () -> PlayResult.failure(" "));
    }
}
