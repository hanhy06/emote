package io.github.hanhy06.emote.config;

import io.github.hanhy06.emote.config.data.EmoteAccessConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ConfigManagerTest {
    @Test
    void installsBundledAnimationsWhenEmoteDirectoryIsAbsent(@TempDir Path tempDir) throws IOException {
        Path bundledDirectory = tempDir.resolve("bundled");
        Files.createDirectories(bundledDirectory.resolve("nested"));
        Files.writeString(bundledDirectory.resolve("wave.json"), "wave");
        Files.writeString(bundledDirectory.resolve("nested").resolve("bow.json"), "bow");

        ConfigManager manager = new ConfigManager(tempDir, Optional.of(bundledDirectory));
        manager.configure();

        assertEquals("wave", Files.readString(manager.getAnimationDirectory().resolve("wave.json")));
        assertEquals(
            "bow",
            Files.readString(manager.getAnimationDirectory().resolve("nested").resolve("bow.json"))
        );
    }

    @Test
    void doesNotInstallBundledAnimationsWhenEmoteDirectoryAlreadyExists(@TempDir Path tempDir) throws IOException {
        Path bundledDirectory = tempDir.resolve("bundled");
        Files.createDirectories(bundledDirectory);
        Files.writeString(bundledDirectory.resolve("wave.json"), "wave");
        Files.createDirectories(tempDir.resolve("emote"));

        ConfigManager manager = new ConfigManager(tempDir, Optional.of(bundledDirectory));
        manager.configure();

        assertTrue(Files.isDirectory(manager.getAnimationDirectory()));
        assertFalse(Files.exists(manager.getAnimationDirectory().resolve("wave.json")));
    }

    @Test
    void createsJsonEmoteConfiguration(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = new ConfigManager(tempDir);
        manager.configure();

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
        manager.configure();
        Files.writeString(tempDir.resolve("emote").resolve("emotes.json"), """
            {
              "disabled": ["demo:wave"],
              "permissions": [
                {"permission":"emote.default","emotes":["demo:wave"]},
                {
                  "permission":"emote.vip",
                  "emotes":["*"],
                  "idle":{"delay_seconds":600,"emote":["demo:wave","demo:sit"]}
                }
              ]
            }
            """);

        assertTrue(manager.readEmoteAccessConfig());
        assertFalse(manager.getEmoteAccessConfig().isEnabled("demo:wave"));
        assertTrue(manager.getEmoteAccessConfig().isEnabled("demo:bow"));
        assertEquals("emote.default", manager.getEmoteAccessConfig().permissions().getFirst().permission());
        assertEquals(List.of("demo:wave"), manager.getEmoteAccessConfig().permissions().getFirst().emotes());
        assertTrue(manager.getEmoteAccessConfig().permissions().getFirst().idle().isEmpty());
        EmoteAccessConfig.IdleEmote idle = manager.getEmoteAccessConfig().permissions().get(1).idle().orElseThrow();
        assertEquals(600, idle.delaySeconds());
        assertEquals(List.of("demo:wave", "demo:sit"), idle.emote());
    }

    @Test
    void updatesDisabledIdsAndPreservesPermissions(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = new ConfigManager(tempDir);
        manager.configure();
        Files.writeString(tempDir.resolve("emote").resolve("emotes.json"), """
            {
              "disabled":[],
              "permissions":[
                {
                  "permission":"emote.vip",
                  "emotes":["demo:wave"],
                  "idle":{"delay_seconds":300,"emote":["demo:wave"]}
                }
              ]
            }
            """);
        assertTrue(manager.readEmoteAccessConfig());

        assertTrue(manager.setEmoteEnabled("demo:wave", false));
        assertEquals(List.of("demo:wave"), manager.getEmoteAccessConfig().disabled());
        assertEquals(List.of("demo:wave"), manager.getEmoteAccessConfig().permissions().getFirst().emotes());
        assertTrue(manager.getEmoteAccessConfig().permissions().getFirst().idle().isPresent());
        assertTrue(Files.readString(tempDir.resolve("emote").resolve("emotes.json")).contains("demo:wave"));

        assertTrue(manager.setEmoteEnabled("demo:wave", true));
        assertTrue(manager.getEmoteAccessConfig().disabled().isEmpty());
    }

    @Test
    void rejectsBlankId(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = new ConfigManager(tempDir);
        manager.configure();
        Files.writeString(tempDir.resolve("emote").resolve("emotes.json"), """
            {"disabled":["   "]}
            """);

        assertFalse(manager.readEmoteAccessConfig());
        assertTrue(manager.getEmoteAccessConfig().disabled().isEmpty());
    }

    @Test
    void keepsCurrentConfigWhenFieldTypeIsInvalid(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = new ConfigManager(tempDir);
        manager.configure();
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
        manager.configure();
        Files.writeString(tempDir.resolve("emote").resolve("emotes.json"), """
            {"disabled":{"invalid":true}}
            """);

        assertFalse(manager.readEmoteAccessConfig());
        assertTrue(manager.getEmoteAccessConfig().disabled().isEmpty());
    }

    @Test
    void rejectsUnsupportedConfigSchema(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = new ConfigManager(tempDir);
        manager.configure();
        Files.writeString(tempDir.resolve("emote").resolve("config.json"), """
            {"schema_version":2}
            """);

        assertFalse(manager.readConfig());
        assertEquals(1, manager.getConfig().schemaVersion());
    }

    @Test
    void doesNotConfigureFilesDuringConstruction(@TempDir Path tempDir) {
        new ConfigManager(tempDir);

        assertFalse(Files.exists(tempDir.resolve("emote")));
    }

    @Test
    void accessConfigCopiesAndProtectsValues() {
        ArrayList<String> disabled = new ArrayList<>(List.of("demo:wave"));
        ArrayList<EmoteAccessConfig.PermissionEntry> permissions = new ArrayList<>();
        permissions.add(new EmoteAccessConfig.PermissionEntry(
            "emote.default",
            List.of("demo:wave"),
            Optional.empty()
        ));
        EmoteAccessConfig config = new EmoteAccessConfig(disabled, permissions);

        disabled.clear();
        permissions.clear();

        assertFalse(config.isEnabled("demo:wave"));
        assertEquals(List.of("demo:wave"), config.disabled());
        assertEquals("emote.default", config.permissions().getFirst().permission());
        assertThrows(UnsupportedOperationException.class, () -> config.disabled().add("demo:bow"));
        assertThrows(UnsupportedOperationException.class, () -> config.permissions().add(
            new EmoteAccessConfig.PermissionEntry("vip", List.of("demo:bow"), Optional.empty())
        ));
    }

    @Test
    void rejectsInvalidIdleConfiguration(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = new ConfigManager(tempDir);
        manager.configure();
        Files.writeString(tempDir.resolve("emote").resolve("emotes.json"), """
            {
              "permissions":[
                {
                  "permission":"emote.default",
                  "emotes":["*"],
                  "idle":{"delay_seconds":0,"emote":["demo:wave"]}
                }
              ]
            }
            """);

        assertFalse(manager.readEmoteAccessConfig());
        assertTrue(manager.getEmoteAccessConfig().permissions().getFirst().idle().isEmpty());
    }
}
