package io.github.hanhy06.emote.config;

import io.github.hanhy06.emote.config.data.EmoteAccessConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigManagerTest {
    @Test
    void createsJsonEmoteConfiguration(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = new ConfigManager(tempDir);

        Path accessPath = tempDir.resolve("emote").resolve("emotes.json");
        assertTrue(Files.exists(accessPath));
        String accessJson = Files.readString(accessPath);
        assertTrue(accessJson.contains("\"disabled\""));
        assertTrue(accessJson.contains("\"permissions\""));
        assertTrue(accessJson.contains("\"emote.default\""));
        assertEquals(1, manager.getConfig().schemaVersion());
        assertTrue(Files.isDirectory(manager.getAnimationDirectory()));
    }

    @Test
    void readsDisabledIdsAndPermissionGroups(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = new ConfigManager(tempDir);
        Files.writeString(tempDir.resolve("emote").resolve("emotes.json"), """
            {
              "disabled": ["demo:wave"],
              "permissions": {
                "emote.default": ["demo:wave"],
                "emote.vip": ["*"]
              }
            }
            """);

        assertTrue(manager.readEmoteAccessConfig());
        assertFalse(manager.getEmoteAccessConfig().isEnabled("demo:wave"));
        assertTrue(manager.getEmoteAccessConfig().isEnabled("demo:bow"));
        assertEquals(List.of("demo:wave"), manager.getEmoteAccessConfig().permissions().get("emote.default"));
    }

    @Test
    void updatesDisabledIdsAndPreservesPermissions(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = new ConfigManager(tempDir);
        Files.writeString(tempDir.resolve("emote").resolve("emotes.json"), """
            {"disabled":[],"permissions":{"emote.vip":["demo:wave"]}}
            """);
        assertTrue(manager.readEmoteAccessConfig());

        assertTrue(manager.setEmoteEnabled("demo:wave", false));
        assertEquals(List.of("demo:wave"), manager.getEmoteAccessConfig().disabled());
        assertEquals(List.of("demo:wave"), manager.getEmoteAccessConfig().permissions().get("emote.vip"));
        assertTrue(Files.readString(tempDir.resolve("emote").resolve("emotes.json")).contains("demo:wave"));

        assertTrue(manager.setEmoteEnabled("demo:wave", true));
        assertTrue(manager.getEmoteAccessConfig().disabled().isEmpty());
    }

    @Test
    void rejectsBlankId(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = new ConfigManager(tempDir);
        Files.writeString(tempDir.resolve("emote").resolve("emotes.json"), """
            {"disabled":["   "]}
            """);

        assertFalse(manager.readEmoteAccessConfig());
        assertTrue(manager.getEmoteAccessConfig().disabled().isEmpty());
    }

    @Test
    void keepsCurrentConfigWhenFieldTypeIsInvalid(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = new ConfigManager(tempDir);
        int currentPageSize = manager.getConfig().menuPageSize();
        Files.writeString(tempDir.resolve("emote").resolve("config.json"), """
            {"menu_page_size":{"invalid":true}}
            """);

        assertFalse(manager.readConfig());
        assertEquals(currentPageSize, manager.getConfig().menuPageSize());
    }

    @Test
    void keepsCurrentAccessConfigWhenFieldTypeIsInvalid(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = new ConfigManager(tempDir);
        Files.writeString(tempDir.resolve("emote").resolve("emotes.json"), """
            {"disabled":{"invalid":true}}
            """);

        assertFalse(manager.readEmoteAccessConfig());
        assertTrue(manager.getEmoteAccessConfig().disabled().isEmpty());
    }

    @Test
    void rejectsUnsupportedConfigSchema(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = new ConfigManager(tempDir);
        Files.writeString(tempDir.resolve("emote").resolve("config.json"), """
            {"schema_version":2}
            """);

        assertFalse(manager.readConfig());
        assertEquals(1, manager.getConfig().schemaVersion());
    }

    @Test
    void accessConfigCopiesAndProtectsValues() {
        ArrayList<String> disabled = new ArrayList<>(List.of("demo:wave"));
        LinkedHashMap<String, List<String>> permissions = new LinkedHashMap<>();
        permissions.put("emote.default", List.of("demo:wave"));
        EmoteAccessConfig config = new EmoteAccessConfig(disabled, permissions);

        disabled.clear();
        permissions.clear();

        assertFalse(config.isEnabled("demo:wave"));
        assertEquals(List.of("demo:wave"), config.disabled());
        assertEquals(Map.of("emote.default", List.of("demo:wave")), config.permissions());
        assertThrows(UnsupportedOperationException.class, () -> config.disabled().add("demo:bow"));
        assertThrows(UnsupportedOperationException.class, () -> config.permissions().put("vip", List.of("demo:bow")));
    }
}
