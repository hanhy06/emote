package io.github.hanhy06.emote.application;

import io.github.hanhy06.emote.content.EmoteCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.hanhy06.emote.content.PreparedEmoteFixture.create;
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
    void delegatesStandaloneVisibilityToThePlaybackPolicy() {
        EmoteCatalog registry = new EmoteCatalog();
        registry.replace(List.of(
            create("demo:wave", "Wave"),
            create("demo:bow", "Bow"),
            create("demo:sit_idle", "Sit Idle", false)
        ), List.of());
        EmoteQueryService service = new EmoteQueryService(
            registry,
            (ignoredPlayer, emote) -> !emote.id().equals("demo:bow")
        );

        assertEquals(List.of("Sit Idle", "Wave"), service.getAll(null).stream().map(EmoteSummary::displayName).toList());
        assertEquals(List.of("demo:sit_idle", "demo:wave"), service.getPlayableIds(null));
        assertEquals(List.of("demo:bow", "demo:wave"), service.getAllIds());
    }
}
