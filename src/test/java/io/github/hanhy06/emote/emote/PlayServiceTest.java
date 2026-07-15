package io.github.hanhy06.emote.emote;

import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.hanhy06.emote.test.RegisteredEmoteFixture.create;
import static org.junit.jupiter.api.Assertions.*;

class PlayServiceTest {
    @Test
    void playReturnsSuccess() {
        PlayService service = new PlayService(
            createRegistry(),
            (ignoredPlayer, ignoredDefinition) -> true,
            (ignoredPlayer, ignoredDefinition) -> PlayResult.SUCCESS
        );

        PlayResult result = service.play(null, "minecraft:wave");

        assertTrue(result.isSuccess());
    }

    @Test
    void playReturnsPlaybackFailure() {
        PlayService service = new PlayService(
            createRegistry(),
            (ignoredPlayer, ignoredDefinition) -> true,
            (ignoredPlayer, ignoredDefinition) -> PlayResult.failure(" Animation unavailable. ")
        );

        PlayResult result = service.play(null, "minecraft:wave");

        assertFalse(result.isSuccess());
        assertEquals("Animation unavailable.", result.errorMessage());
    }

    @Test
    void selectionRequiresTheExactId() {
        PlayService service = new PlayService(
            createRegistry(),
            (ignoredPlayer, ignoredDefinition) -> true,
            (ignoredPlayer, ignoredDefinition) -> PlayResult.SUCCESS
        );

        assertTrue(service.play(null, "minecraft:wave").isSuccess());
        assertEquals("Unknown: wave", service.play(null, "wave").errorMessage());
    }

    @Test
    void rejectsBlockedEmote() {
        PlayService service = new PlayService(
            createRegistry(),
            (ignoredPlayer, ignoredDefinition) -> false,
            (ignoredPlayer, ignoredDefinition) -> PlayResult.SUCCESS
        );

        assertEquals("No emote permission.", service.play(null, "minecraft:wave").errorMessage());
    }

    private EmoteRegistry createRegistry() {
        EmoteRegistry registry = new EmoteRegistry();
        registry.replace(List.of(create("wave", "Wave")));
        return registry;
    }
}
