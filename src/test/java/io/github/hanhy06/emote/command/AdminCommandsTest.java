package io.github.hanhy06.emote.command;

import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class AdminCommandsTest {
    @Test
    void listEntrySeparatesFieldsAcrossReadableLines() {
        var entry = AdminCommands.createListEntry(
            "emote:dance",
            "Dance",
            "A looping dance",
            12,
            85,
            "emote.dance.json",
            "loop",
            true,
            false
        );

        assertEquals(
            """

            • emote:dance
              Dance — A looping dance
              Nodes: 12  Time: 85 ticks (4.3s)
              Source: emote.dance.json
              Loop: loop  Standalone: yes  Player: visible
            """.stripTrailing(),
            entry.getString()
        );
        assertEquals(TextColor.DARK_GRAY, entry.getStyle().getColor());
        assertEquals(TextColor.AQUA, entry.getSiblings().getFirst().getStyle().getColor());
    }
}
