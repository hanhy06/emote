package io.github.hanhy06.emote.config;

import io.github.hanhy06.emote.config.data.PackConfig;
import io.github.hanhy06.emote.config.data.PackOverride;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

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
        assertTrue(Files.readString(tempDir.resolve("emote").resolve("config.json")).contains("\"schema_version\": 1"));
        assertEquals(1, manager.getConfig().schemaVersion());
    }

    @Test
    void readsEnabledAndPermissionOverrides(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = new ConfigManager(tempDir);
        Files.writeString(tempDir.resolve("emote").resolve("packs.json"), """
            {
              "packs": {
                "wave_pack": {
                  "enabled": false,
                  "permission": "emote.pack.vip"
                }
              }
            }
            """);

        assertTrue(manager.readPackConfig());
        assertFalse(manager.getPackConfig().isEnabled("wave_pack"));
        assertTrue(manager.getPackConfig().isEnabled("unconfigured_pack"));
        assertEquals("emote.pack.vip", manager.getPackConfig().findOverride("wave_pack").permission());
    }

    @Test
    void updatesPackEnabledStateAndPreservesPermission(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = new ConfigManager(tempDir);
        Files.writeString(tempDir.resolve("emote").resolve("packs.json"), """
            {"packs":{"wave_pack":{"enabled":true,"permission":"emote.pack.vip"}}}
            """);
        assertTrue(manager.readPackConfig());

        assertTrue(manager.setPackEnabled("wave_pack", false));

        PackOverride packOverride = manager.getPackConfig().findOverride("wave_pack");
        assertFalse(packOverride.enabled());
        assertEquals("emote.pack.vip", packOverride.permission());
        String savedConfig = Files.readString(tempDir.resolve("emote").resolve("packs.json"));
        assertTrue(savedConfig.contains("\"enabled\": false"));
        assertTrue(savedConfig.contains("\"permission\": \"emote.pack.vip\""));
    }

    @Test
    void rejectsBlankNamespace(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = new ConfigManager(tempDir);
        Files.writeString(tempDir.resolve("emote").resolve("packs.json"), """
            {"packs":{"   ":{"enabled":true,"permission":""}}}
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
            {"packs":{"wave_pack":{"enabled":{"invalid":true},"permission":""}}}
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
        source.put("wave", new PackOverride(true, ""));
        PackConfig config = new PackConfig(source);

        source.clear();

        assertTrue(config.isEnabled("wave"));
        assertEquals(1, config.packs().size());
        assertThrows(UnsupportedOperationException.class, () -> config.packs().put("bow", new PackOverride(true, "")));
    }
}
