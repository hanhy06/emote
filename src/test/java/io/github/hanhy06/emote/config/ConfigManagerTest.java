package io.github.hanhy06.emote.config;

import io.github.hanhy06.emote.config.data.PackConfig;
import io.github.hanhy06.emote.config.data.PackOverride;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        assertTrue(Files.readString(packsPath).contains("\"packs\""));
        assertTrue(Files.readString(packsPath).contains("\"permissions\""));
        assertTrue(Files.readString(packsPath).contains("\"default\""));
        assertTrue(Files.readString(tempDir.resolve("emote").resolve("config.json")).contains("\"schema_version\": 1"));
        assertFalse(Files.readString(tempDir.resolve("emote").resolve("config.json")).contains("emote_permission"));
        assertEquals(1, manager.getConfig().schemaVersion());
    }

    @Test
    void readsEnabledOverridesAndPermissionGroups(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = new ConfigManager(tempDir);
        Files.writeString(tempDir.resolve("emote").resolve("packs.json"), """
            {
              "packs": {
                "wave_pack": {
                  "enabled": false
                }
              },
              "permissions": {
                "default": ["wave_pack"],
                "emote.pack.vip": ["*"]
              }
            }
            """);

        assertTrue(manager.readPackConfig());
        assertFalse(manager.getPackConfig().isEnabled("wave_pack"));
        assertTrue(manager.getPackConfig().isEnabled("unconfigured_pack"));
        assertEquals(List.of("wave_pack"), manager.getPackConfig().permissions().get("default"));
        assertEquals(List.of("*"), manager.getPackConfig().permissions().get("emote.pack.vip"));
    }

    @Test
    void updatesPackEnabledStateAndPreservesPermissionGroups(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = new ConfigManager(tempDir);
        Files.writeString(tempDir.resolve("emote").resolve("packs.json"), """
            {
              "packs":{"wave_pack":{"enabled":true}},
              "permissions":{"emote.pack.vip":["wave_pack"]}
            }
            """);
        assertTrue(manager.readPackConfig());

        assertTrue(manager.setPackEnabled("wave_pack", false));

        PackOverride packOverride = manager.getPackConfig().findOverride("wave_pack");
        assertFalse(packOverride.enabled());
        assertEquals(List.of("wave_pack"), manager.getPackConfig().permissions().get("emote.pack.vip"));
        String savedConfig = Files.readString(tempDir.resolve("emote").resolve("packs.json"));
        assertTrue(savedConfig.contains("\"enabled\": false"));
        assertTrue(savedConfig.contains("\"emote.pack.vip\""));
        assertTrue(savedConfig.contains("\"wave_pack\""));
    }

    @Test
    void rejectsBlankNamespace(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = new ConfigManager(tempDir);
        Files.writeString(tempDir.resolve("emote").resolve("packs.json"), """
            {"packs":{"   ":{"enabled":true}}}
            """);

        assertFalse(manager.readPackConfig());
        assertTrue(manager.getPackConfig().packs().isEmpty());
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
            {"packs":{"wave_pack":{"enabled":{"invalid":true}}}}
            """);

        assertFalse(manager.readPackConfig());
        assertTrue(manager.getPackConfig().packs().isEmpty());
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
    void packConfigCopiesAndProtectsOverrides() {
        LinkedHashMap<String, PackOverride> source = new LinkedHashMap<>();
        source.put("wave", new PackOverride(true));
        LinkedHashMap<String, List<String>> permissions = new LinkedHashMap<>();
        permissions.put("default", List.of("wave"));
        PackConfig config = new PackConfig(source, permissions);

        source.clear();
        permissions.clear();

        assertTrue(config.isEnabled("wave"));
        assertEquals(1, config.packs().size());
        assertEquals(Map.of("default", List.of("wave")), config.permissions());
        assertThrows(UnsupportedOperationException.class, () -> config.packs().put("bow", new PackOverride(true)));
        assertThrows(UnsupportedOperationException.class, () -> config.permissions().put("vip", List.of("bow")));
    }
}
