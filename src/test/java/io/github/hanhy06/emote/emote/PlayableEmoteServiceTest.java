package io.github.hanhy06.emote.emote;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayableEmoteServiceTest {
    @Test
    void getPlayableEmotesCreatesOneEntryPerVisibleDatapack() {
        EmoteRegistry registry = new EmoteRegistry();
        registry.replaceDefinitions(List.of(
            createDefinition("wave_pack", "wave", "Wave"),
            createDefinition("bow_pack", "bow", "Bow")
        ));
        PlayableEmoteService service = new PlayableEmoteService(
            registry,
            (player, definition) -> !definition.namespace().equals("bow_pack")
        );

        List<PlayableEmote> emotes = service.getPlayableEmotes(null);

        assertEquals(List.of("Wave"), emotes.stream().map(PlayableEmote::displayName).toList());
        assertEquals(List.of("wave", "wave_pack"), service.getPlayablePlayNames(null));
    }

    @Test
    void findSelectionReturnsTheDatapackEntrypoint() {
        EmoteRegistry registry = new EmoteRegistry();
        registry.replaceDefinitions(List.of(createDefinition("wave_pack", "wave", "Wave")));
        PlayableEmoteService service = new PlayableEmoteService(registry, (player, definition) -> true);

        PlayableEmoteSelection result = service.findSelection(null, "wave");

        assertTrue(result.isSuccess());
        assertEquals("a/default/play_anim_loop", result.definition().entrypoint());
    }

    @Test
    void findSelectionRejectsBlockedDatapack() {
        EmoteRegistry registry = new EmoteRegistry();
        registry.replaceDefinitions(List.of(createDefinition("wave_pack", "wave", "Wave")));
        PlayableEmoteService service = new PlayableEmoteService(registry, (player, definition) -> false);

        PlayableEmoteSelection result = service.findSelection(null, "wave");

        assertFalse(result.isSuccess());
        assertEquals("No emote permission.", result.errorMessage());
    }

    private EmoteDefinition createDefinition(String namespace, String commandName, String name) {
        return new EmoteDefinition(
            namespace,
            name,
            name + " description",
            commandName,
            "a/default/play_anim_loop",
            true,
            Path.of(namespace + "-pack"),
            1,
            List.of()
        );
    }
}
