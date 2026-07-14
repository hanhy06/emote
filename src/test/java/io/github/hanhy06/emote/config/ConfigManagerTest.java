package io.github.hanhy06.emote.config;

import io.github.hanhy06.emote.config.data.PackConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigManagerTest {
    @Test
    void createsMinimalPackConfig(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = new ConfigManager(tempDir);

        Path packsPath = tempDir.resolve("emote").resolve("packs.json");
        assertTrue(Files.exists(packsPath));
        assertTrue(Files.readString(packsPath).contains("\"disabled\""));
        assertTrue(Files.readString(packsPath).contains("\"permissions\""));
        assertTrue(Files.readString(packsPath).contains("\"emote.default\""));
        assertTrue(Files.readString(packsPath).contains("\"*\""));
        assertTrue(Files.readString(tempDir.resolve("emote").resolve("config.json")).contains("\"schema_version\": 1"));
        assertFalse(Files.readString(tempDir.resolve("emote").resolve("config.json")).contains("emote_permission"));
        assertEquals(1, manager.getConfig().schemaVersion());
    }

    @Test
    void readsDisabledNamespacesAndPermissionGroups(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = new ConfigManager(tempDir);
        Files.writeString(tempDir.resolve("emote").resolve("packs.json"), """
            {
              "disabled": ["wave_pack"],
              "permissions": {
                "emote.default": ["wave_pack"],
                "emote.pack.vip": ["*"]
              }
            }
            """);

        assertTrue(manager.readPackConfig());
        assertFalse(manager.getPackConfig().isEnabled("wave_pack"));
        assertTrue(manager.getPackConfig().isEnabled("unconfigured_pack"));
        assertEquals(List.of("wave_pack"), manager.getPackConfig().permissions().get("emote.default"));
        assertEquals(List.of("*"), manager.getPackConfig().permissions().get("emote.pack.vip"));
    }

    @Test
    void updatesDisabledNamespacesAndPreservesPermissionGroups(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = new ConfigManager(tempDir);
        Files.writeString(tempDir.resolve("emote").resolve("packs.json"), """
            {
              "disabled": [],
              "permissions":{"emote.pack.vip":["wave_pack"]}
            }
            """);
        assertTrue(manager.readPackConfig());

        assertTrue(manager.setPackEnabled("wave_pack", false));

        assertEquals(List.of("wave_pack"), manager.getPackConfig().disabled());
        assertEquals(List.of("wave_pack"), manager.getPackConfig().permissions().get("emote.pack.vip"));
        String savedConfig = Files.readString(tempDir.resolve("emote").resolve("packs.json"));
        assertTrue(savedConfig.contains("\"disabled\""));
        assertTrue(savedConfig.contains("\"emote.pack.vip\""));
        assertTrue(savedConfig.contains("\"wave_pack\""));

        assertTrue(manager.setPackEnabled("wave_pack", true));
        assertTrue(manager.getPackConfig().disabled().isEmpty());
    }

    @Test
    void rejectsBlankNamespace(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = new ConfigManager(tempDir);
        Files.writeString(tempDir.resolve("emote").resolve("packs.json"), """
            {"disabled":["   "]}
            """);

        assertFalse(manager.readPackConfig());
        assertTrue(manager.getPackConfig().disabled().isEmpty());
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
    void keepsCurrentPackConfigWhenFieldTypeIsInvalid(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = new ConfigManager(tempDir);
        Files.writeString(tempDir.resolve("emote").resolve("packs.json"), """
            {"disabled":{"invalid":true}}
            """);

        assertFalse(manager.readPackConfig());
        assertTrue(manager.getPackConfig().disabled().isEmpty());
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
    void packConfigCopiesAndProtectsValues() {
        ArrayList<String> disabled = new ArrayList<>(List.of("wave"));
        LinkedHashMap<String, List<String>> permissions = new LinkedHashMap<>();
        permissions.put("emote.default", List.of("wave"));
        PackConfig config = new PackConfig(disabled, permissions);

        disabled.clear();
        permissions.clear();

        assertFalse(config.isEnabled("wave"));
        assertEquals(List.of("wave"), config.disabled());
        assertEquals(Map.of("emote.default", List.of("wave")), config.permissions());
        assertThrows(UnsupportedOperationException.class, () -> config.disabled().add("bow"));
        assertThrows(UnsupportedOperationException.class, () -> config.permissions().put("vip", List.of("bow")));
    }
}
