package io.github.hanhy06.emote.dialog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DialogManagerTest {
    @Test
    void searchTemplateQuotesMultiwordQuery() {
        assertEquals("/emote search \"$(query)\"", DialogManager.SEARCH_COMMAND_TEMPLATE);
    }

    @Test
    void searchButtonFillsRemainingColumnForOddButtonCount() {
        assertEquals(150, DialogManager.searchButtonWidth(5));
        assertEquals(310, DialogManager.searchButtonWidth(4));
    }

    @Test
    void pageCommandUsesRootPageArgumentWithoutMenuSubcommand() {
        assertEquals("/emote 2", DialogManager.createPageCommand(2, ""));
        assertEquals("/emote search wave 2", DialogManager.createPageCommand(2, "wave"));
    }
}
