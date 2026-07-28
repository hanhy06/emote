package io.github.hanhy06.emote.emote;

import io.github.hanhy06.emote.api.PlayResult;
import io.github.hanhy06.emote.api.PlaySource;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.hanhy06.emote.emote.RegisteredEmoteFixture.create;
import static org.junit.jupiter.api.Assertions.*;

class PlayServiceTest {
    @Test
    void playReturnsSuccess() {
        PlayService service = new PlayService(
            createRegistry(),
            (ignoredPlayer, ignoredDefinition) -> true,
            (ignoredPlayer, ignoredDefinition) -> PlayResult.SUCCESS,
            (ignoredPlayer, ignoredEmote, ignoredSource) -> null
        );

        PlayResult result = service.play(null, "minecraft:wave");

        assertTrue(result.isSuccess());
    }

    @Test
    void playReturnsPlaybackFailure() {
        PlayService service = new PlayService(
            createRegistry(),
            (ignoredPlayer, ignoredDefinition) -> true,
            (ignoredPlayer, ignoredDefinition) -> PlayResult.failure(" Animation unavailable. "),
            (ignoredPlayer, ignoredEmote, ignoredSource) -> null
        );

        PlayResult result = service.play(null, "minecraft:wave");

        assertFalse(result.isSuccess());
        assertEquals("Animation unavailable.", result.errorMessage().getString());
    }

    @Test
    void selectionRequiresTheExactId() {
        PlayService service = new PlayService(
            createRegistry(),
            (ignoredPlayer, ignoredDefinition) -> true,
            (ignoredPlayer, ignoredDefinition) -> PlayResult.SUCCESS,
            (ignoredPlayer, ignoredEmote, ignoredSource) -> null
        );

        assertTrue(service.play(null, "minecraft:wave").isSuccess());
        assertEquals("Unknown: wave", service.play(null, "wave").errorMessage().getString());
    }

    @Test
    void rejectsBlockedEmote() {
        PlayService service = new PlayService(
            createRegistry(),
            (ignoredPlayer, ignoredDefinition) -> false,
            (ignoredPlayer, ignoredDefinition) -> PlayResult.SUCCESS,
            (ignoredPlayer, ignoredEmote, ignoredSource) -> null
        );

        assertEquals("No emote permission.", service.play(null, "minecraft:wave").errorMessage().getString());
    }

    @Test
    void listenerCanCancelPlaybackWithAComponentMessage() {
        PlayService service = new PlayService(
            createRegistry(),
            (ignoredPlayer, ignoredDefinition) -> true,
            (ignoredPlayer, ignoredDefinition) -> fail("Cancelled playback must not start"),
            (ignoredPlayer, ignoredEmote, ignoredSource) ->
                Component.literal("Playback blocked by another mod.")
        );

        PlayResult result = service.play(null, "minecraft:wave", PlaySource.API);

        assertFalse(result.isSuccess());
        assertEquals("Playback blocked by another mod.", result.errorMessage().getString());
    }

    private EmoteRegistry createRegistry() {
        EmoteRegistry registry = new EmoteRegistry();
        registry.replace(List.of(create("wave", "Wave")));
        return registry;
    }
}
