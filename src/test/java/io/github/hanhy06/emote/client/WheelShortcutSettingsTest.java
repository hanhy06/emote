package io.github.hanhy06.emote.client;

import io.github.hanhy06.emote.emote.PlayableEmote;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WheelShortcutSettingsTest {
    @Test
    void keepsSavedOrderWhenServerAddsAnotherEmote(@TempDir Path tempDir) {
        Path filePath = tempDir.resolve("emote/wheel-shortcuts.json");
        WheelShortcutSettings settings = new WheelShortcutSettings(filePath);

        settings.updateServer("server:example.test", emotes("a", "b", "c"));
        settings.updateServer("server:example.test", emotes("a", "b", "c", "d"));

        assertEquals(List.of("a", "b", "c"), ids(settings.selectedEmotes()));
        assertEquals(List.of("d"), ids(settings.availableEmotes()));
    }

    @Test
    void savesSelectionAndOrderingPerServer(@TempDir Path tempDir) {
        Path filePath = tempDir.resolve("emote/wheel-shortcuts.json");
        WheelShortcutSettings settings = new WheelShortcutSettings(filePath);
        List<PlayableEmote> available = emotes("a", "b", "c", "d");

        settings.updateServer("server:first.test", available);
        settings.remove("d");
        settings.moveUp("c");
        settings.moveUp("c");
        settings.moveDown("c");

        settings.updateServer("server:second.test", emotes("x", "y"));
        settings.moveUp("y");

        WheelShortcutSettings reloaded = new WheelShortcutSettings(filePath);
        reloaded.updateServer("server:first.test", available);
        assertEquals(List.of("a", "c", "b"), ids(reloaded.selectedEmotes()));
        assertEquals(List.of("d"), ids(reloaded.availableEmotes()));

        reloaded.updateServer("server:second.test", emotes("x", "y"));
        assertEquals(List.of("y", "x"), ids(reloaded.selectedEmotes()));
    }

    private static List<PlayableEmote> emotes(String... ids) {
        return java.util.Arrays.stream(ids)
            .map(id -> new PlayableEmote(id, id.toUpperCase(), "Description " + id))
            .toList();
    }

    private static List<String> ids(List<PlayableEmote> emotes) {
        return emotes.stream().map(PlayableEmote::id).toList();
    }
}
