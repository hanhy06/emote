package io.github.hanhy06.emote.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmoteSummaryTest {
    @Test
    void createPlayCommandUsesThePublicPlayCommand() {
        EmoteSummary emote = new EmoteSummary("wave", "Wave", "Friendly wave");

        assertEquals("emote play wave", emote.createPlayCommand());
    }
}
