package io.github.hanhy06.emote.command;

import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class UserCommandTest {
    @Test
    void suggestsFullNamespacedIdsFromPathPrefix() throws Exception {
        var suggestions = UserCommand.suggestPlayIds(
            List.of("emote:cry", "emote:wave", "other:clap"),
            new SuggestionsBuilder("c", 0)
        ).get();

        assertEquals(List.of("emote:cry", "other:clap"), suggestions.getList().stream().map(Suggestion::getText).toList());
    }
}
