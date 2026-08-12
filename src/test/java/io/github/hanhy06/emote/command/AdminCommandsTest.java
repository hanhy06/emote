package io.github.hanhy06.emote.command;

import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class AdminCommandsTest {
    @Test
    void listEntrySeparatesFieldsAcrossReadableLines() {
        var entry = AdminCommands.createListEntry("emote:dance", "Dance", 12, "emote.dance.json");

        assertEquals(
            "\n• emote:dance\n  Name: Dance\n  Nodes: 12  Source: emote.dance.json",
            entry.getString()
        );
        assertEquals(TextColor.DARK_GRAY, entry.getStyle().getColor());
        assertEquals(TextColor.AQUA, entry.getSiblings().getFirst().getStyle().getColor());
    }
}
