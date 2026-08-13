package io.github.hanhy06.emote.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MinecraftTimeTest {
    @Test
    void parsesMinecraftTimeUnitsAndBareTicks() {
        assertEquals(200, MinecraftTime.parse("200", 0));
        assertEquals(200, MinecraftTime.parse("200t", 0));
        assertEquals(200, MinecraftTime.parse("10s", 0));
        assertEquals(10, MinecraftTime.parse("0.5s", 0));
        assertEquals(24_000, MinecraftTime.parse("1d", 0));
    }

    @Test
    void rejectsUnsupportedTrailingAndBelowMinimumValues() {
        assertThrows(IllegalArgumentException.class, () -> MinecraftTime.parse("1m", 0));
        assertThrows(IllegalArgumentException.class, () -> MinecraftTime.parse("20t trailing", 0));
        assertThrows(IllegalArgumentException.class, () -> MinecraftTime.parse("0t", 1));
    }
}
