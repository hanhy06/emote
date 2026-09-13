package io.github.hanhy06.emote.content;

import io.github.hanhy06.emote.api.PlayResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayResultTest {
    @Test
    void failureRejectsBlankErrorMessage() {
        assertThrows(IllegalArgumentException.class, () -> PlayResult.failure(" "));
    }
}
