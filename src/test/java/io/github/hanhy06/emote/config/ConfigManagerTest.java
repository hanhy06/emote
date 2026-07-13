package io.github.hanhy06.emote.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigManagerTest {
    @Test
    void createsMinimalPackConfig(@TempDir Path tempDir) throws IOException {
        new ConfigManager(tempDir);

        Path packsPath = tempDir.resolve("emote").resolve("packs.json");
        assertTrue(Files.exists(packsPath));
        assertTrue(Files.readString(packsPath).contains("\"packs\""));
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
        assertTrue(manager.getPackConfig().findOverride("wave_pack").permission().equals("emote.pack.vip"));
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
}
