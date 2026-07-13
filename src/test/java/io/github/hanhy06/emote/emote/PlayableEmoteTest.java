package io.github.hanhy06.emote.emote;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayableEmoteTest {
    @Test
    void createPlayCommandUsesThePublicPlayCommand() {
        PlayableEmote emote = new PlayableEmote("wave", "Wave", "Friendly wave");

        assertEquals("emote play wave", emote.createPlayCommand());
    }
}
