package io.github.hanhy06.emote.emote;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayResultTest {
    @Test
    void successHasNoErrorMessage() {
        assertTrue(PlayResult.SUCCESS.isSuccess());
    }

    @Test
    void failureTrimsErrorMessage() {
        PlayResult result = PlayResult.failure(" Animation unavailable. ");

        assertFalse(result.isSuccess());
        assertEquals("Animation unavailable.", result.errorMessage().getString());
    }

    @Test
    void failureRejectsBlankErrorMessage() {
        assertThrows(IllegalArgumentException.class, () -> PlayResult.failure(" "));
    }
}
