package io.github.hanhy06.emote.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.hanhy06.emote.config.JsonFileStore;
import io.github.hanhy06.emote.application.EmoteSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WheelShortcutSettingsTest {
    @Test
    void appendsNewServerEmoteWithoutChangingSavedOrder(@TempDir Path tempDir) {
        Path filePath = tempDir.resolve("emote/wheel-shortcuts.json");
        WheelShortcutSettings settings = new WheelShortcutSettings(filePath);

        settings.updateServer("server:example.test", emotes("a", "b", "c"));
        settings.updateServer("server:example.test", emotes("a", "b", "c", "d"));

        assertEquals(List.of("a", "b", "c", "d"), ids(settings.selectedEmotes()));
        assertEquals(List.of(), ids(settings.availableEmotes()));
    }

    @Test
    void savesSelectionAndOrderingPerServer(@TempDir Path tempDir) {
        Path filePath = tempDir.resolve("emote/wheel-shortcuts.json");
        WheelShortcutSettings settings = new WheelShortcutSettings(filePath);
        List<EmoteSummary> available = emotes("a", "b", "c", "d");

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

    @Test
    void wrapsMovementBetweenFirstAndLastPositions(@TempDir Path tempDir) {
        WheelShortcutSettings settings = new WheelShortcutSettings(tempDir.resolve("emote/wheel-shortcuts.json"));
        settings.updateServer("server:example.test", emotes("a", "b", "c"));

        settings.moveUp("a");
        assertEquals(List.of("b", "c", "a"), ids(settings.selectedEmotes()));

        settings.moveDown("a");
        assertEquals(List.of("a", "b", "c"), ids(settings.selectedEmotes()));

        settings.moveDown("c");
        assertEquals(List.of("c", "a", "b"), ids(settings.selectedEmotes()));
    }

    @Test
    void restoresSelectionSnapshotAfterEditing(@TempDir Path tempDir) {
        WheelShortcutSettings settings = new WheelShortcutSettings(tempDir.resolve("emote/wheel-shortcuts.json"));
        settings.updateServer("server:example.test", emotes("a", "b", "c"));
        List<String> snapshot = List.copyOf(settings.selectedIds());

        settings.remove("a");
        settings.moveDown("b");
        settings.replaceSelectedIds(snapshot);

        assertEquals(List.of("a", "b", "c"), ids(settings.selectedEmotes()));
    }

    @Test
    void migratesExistingSettingsWithoutRestoringRemovedEmotes(@TempDir Path tempDir) throws IOException {
        Path filePath = tempDir.resolve("emote/wheel-shortcuts.json");
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", 1);
        JsonObject servers = new JsonObject();
        JsonArray selectedIds = new JsonArray();
        selectedIds.add("a");
        servers.add("server:example.test", selectedIds);
        root.add("servers", servers);
        JsonFileStore.writeObjectAtomically(filePath, root, new Gson());

        WheelShortcutSettings settings = new WheelShortcutSettings(filePath);
        settings.updateServer("server:example.test", emotes("a", "b"));
        assertEquals(List.of("a"), ids(settings.selectedEmotes()));
        assertEquals(List.of("b"), ids(settings.availableEmotes()));

        settings.updateServer("server:example.test", emotes("a", "b", "c"));
        assertEquals(List.of("a", "c"), ids(settings.selectedEmotes()));
        assertEquals(List.of("b"), ids(settings.availableEmotes()));
    }

    private static List<EmoteSummary> emotes(String... ids) {
        return java.util.Arrays.stream(ids)
            .map(id -> new EmoteSummary(id, id.toUpperCase(), "Description " + id))
            .toList();
    }

    private static List<String> ids(List<EmoteSummary> emotes) {
        return emotes.stream().map(EmoteSummary::id).toList();
    }
}
