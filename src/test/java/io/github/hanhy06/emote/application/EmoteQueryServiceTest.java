package io.github.hanhy06.emote.application;

import io.github.hanhy06.emote.content.EmoteCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.hanhy06.emote.content.PreparedAnimationFixture.create;
import static org.junit.jupiter.api.Assertions.assertEquals;

class EmoteQueryServiceTest {
    @Test
    void searchFiltersAndRanksEmoteSummarys() {
        List<EmoteSummary> emotes = List.of(
            new EmoteSummary("demo:dance", "Fast Dance", "Quick movement"),
            new EmoteSummary("demo:wave", "Wave", "Friendly greeting"),
            new EmoteSummary("demo:wave_fast", "Other", "Wave variation")
        );

        assertEquals(
            List.of("demo:wave", "demo:wave_fast"),
            EmoteQueryService.filter(emotes, "WAVE").stream().map(EmoteSummary::id).toList()
        );
        assertEquals(
            List.of("demo:dance"),
            EmoteQueryService.filter(emotes, "quick").stream().map(EmoteSummary::id).toList()
        );
    }

    @Test
    void searchMatchesExactTagsAndCombinesThemWithText() {
        List<EmoteSummary> emotes = List.of(
            new EmoteSummary("demo:fast", "Fast Dance", "Quick movement #Dance #Solo"),
            new EmoteSummary("demo:group", "Group Dance", "Dance together #dance #group"),
            new EmoteSummary("demo:dancer", "Dancer", "A performer #dancer #solo")
        );

        assertEquals(
            List.of("demo:fast", "demo:group"),
            EmoteQueryService.filter(emotes, "#DANCE").stream().map(EmoteSummary::id).toList()
        );
        assertEquals(
            List.of("demo:fast"),
            EmoteQueryService.filter(emotes, "fast #dance #solo").stream().map(EmoteSummary::id).toList()
        );
    }

    @Test
    void clientFilteringPreservesShortcutOrder() {
        List<EmoteSummary> emotes = List.of(
            new EmoteSummary("demo:second", "Second", "#dance"),
            new EmoteSummary("demo:first", "Dance", "#dance")
        );

        assertEquals(
            List.of("demo:second", "demo:first"),
            EmoteSearch.filterPreservingOrder(emotes, "#dance").stream().map(EmoteSummary::id).toList()
        );
    }

    @Test
    void delegatesStandaloneVisibilityToThePlaybackPolicy() {
        EmoteCatalog registry = new EmoteCatalog();
        registry.replace(List.of(
            create("demo:wave", "Wave"),
            create("demo:bow", "Bow"),
            create("demo:sit_idle", "Sit Idle", false)
        ));
        EmoteQueryService service = new EmoteQueryService(
            registry,
            (ignoredPlayer, emote) -> !emote.id().equals("demo:bow")
        );

        assertEquals(List.of("Sit Idle", "Wave"), service.getAll(null).stream().map(EmoteSummary::displayName).toList());
        assertEquals(List.of("demo:sit_idle", "demo:wave"), service.getPlayableIds(null));
        assertEquals(List.of("demo:bow", "demo:wave"), service.getAllIds());
    }
}
