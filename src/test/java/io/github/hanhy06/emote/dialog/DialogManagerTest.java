package io.github.hanhy06.emote.dialog;

import io.github.hanhy06.emote.emote.PlayableEmote;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DialogManagerTest {
    @Test
    void pageCommandUsesRootPageArgumentWithoutMenuSubcommand() {
        assertEquals("/emote 2", DialogManager.createPageCommand(2, ""));
        assertEquals("/emote search wave 2", DialogManager.createPageCommand(2, "wave"));
    }

    @Test
    void searchFiltersAndRanksPlayableEmotes() {
        List<PlayableEmote> emotes = List.of(
            new PlayableEmote("dance", "Fast Dance", "Quick movement"),
            new PlayableEmote("wave", "Wave", "Friendly greeting"),
            new PlayableEmote("wave_fast", "Other", "Wave variation")
        );

        assertEquals(
            List.of("wave", "wave_fast"),
            DialogManager.filterPlayableEmotes(emotes, "WAVE").stream().map(PlayableEmote::commandName).toList()
        );
        assertEquals(
            List.of("dance"),
            DialogManager.filterPlayableEmotes(emotes, "quick").stream().map(PlayableEmote::commandName).toList()
        );
    }
}
