package io.github.hanhy06.emote.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmoteMenuTest {
    @Test
    void searchTemplateQuotesMultiwordQuery() {
        assertEquals("/emote search \"$(query)\"", EmoteMenu.SEARCH_COMMAND_TEMPLATE);
    }

    @Test
    void searchButtonFillsRemainingColumnForOddButtonCount() {
        assertEquals(150, EmoteMenu.searchButtonWidth(5));
        assertEquals(310, EmoteMenu.searchButtonWidth(4));
    }

    @Test
    void pageCommandUsesRootPageArgumentWithoutMenuSubcommand() {
        assertEquals("/emote 2", EmoteMenu.createPageCommand(2, ""));
        assertEquals("/emote search wave 2", EmoteMenu.createPageCommand(2, "wave"));
    }
}
