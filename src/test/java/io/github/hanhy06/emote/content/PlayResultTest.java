package io.github.hanhy06.emote.content;

import io.github.hanhy06.emote.api.PlayResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayResultTest {
    @Test
    void successHasNoErrorMessage() {
        assertTrue(PlayResult.SUCCESS.isSuccess());
    }

    @Test
    void failureProvidesAnErrorMessage() {
        PlayResult result = PlayResult.failure(" Animation unavailable. ");

        assertFalse(result.isSuccess());
        assertFalse(result.errorMessage().getString().isBlank());
    }

    @Test
    void failureRejectsBlankErrorMessage() {
        assertThrows(IllegalArgumentException.class, () -> PlayResult.failure(" "));
    }
}
