package io.github.hanhy06.emote.emote;

import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.hanhy06.emote.test.RegisteredEmoteFixture.create;
import static org.junit.jupiter.api.Assertions.*;

class PlayableEmoteServiceTest {
    @Test
    void searchFiltersAndRanksPlayableEmotes() {
        List<PlayableEmote> emotes = List.of(
            new PlayableEmote("demo:dance", "Fast Dance", "Quick movement"),
            new PlayableEmote("demo:wave", "Wave", "Friendly greeting"),
            new PlayableEmote("demo:wave_fast", "Other", "Wave variation")
        );

        assertEquals(
            List.of("demo:wave", "demo:wave_fast"),
            PlayableEmoteService.filterPlayableEmotes(emotes, "WAVE").stream().map(PlayableEmote::id).toList()
        );
        assertEquals(
            List.of("demo:dance"),
            PlayableEmoteService.filterPlayableEmotes(emotes, "quick").stream().map(PlayableEmote::id).toList()
        );
    }

    @Test
    void exposesOnlyPermittedIds() {
        EmoteRegistry registry = new EmoteRegistry();
        registry.replace(List.of(
            create("demo:wave", "Wave"),
            create("demo:bow", "Bow")
        ));
        PlayableEmoteService service = new PlayableEmoteService(
            registry,
            (ignoredPlayer, definition) -> !definition.id().equals("demo:bow")
        );

        assertEquals(List.of("Wave"), service.getPlayableEmotes(null).stream().map(PlayableEmote::displayName).toList());
        assertEquals(List.of("demo:wave"), service.getPlayablePlayIds(null));
    }

    @Test
    void selectionRequiresTheExactId() {
        EmoteRegistry registry = new EmoteRegistry();
        registry.replace(List.of(create("demo:wave", "Wave")));
        PlayableEmoteService service = new PlayableEmoteService(registry, (ignoredPlayer, ignoredDefinition) -> true);

        assertTrue(service.findSelection(null, "demo:wave").isSuccess());
        assertFalse(service.findSelection(null, "wave").isSuccess());
    }

    @Test
    void findSelectionRejectsBlockedEmote() {
        EmoteRegistry registry = new EmoteRegistry();
        registry.replace(List.of(create("demo:wave", "Wave")));
        PlayableEmoteService service = new PlayableEmoteService(registry, (ignoredPlayer, ignoredDefinition) -> false);

        PlayableEmoteSelection result = service.findSelection(null, "demo:wave");
        assertFalse(result.isSuccess());
        assertEquals("No emote permission.", result.errorMessage());
    }
}
