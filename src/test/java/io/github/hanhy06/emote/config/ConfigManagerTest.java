package io.github.hanhy06.emote.config;

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

        ConfigManager manager = new ConfigManager(tempDir, bundledDirectory);
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

        ConfigManager manager = new ConfigManager(tempDir, bundledDirectory);
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
        assertTrue(accessJson.contains("\"schema_version\": 2"));
        assertTrue(accessJson.contains("\"delay\": \"6000t\""));
        assertTrue(accessJson.contains("\"emote.default\""));
        AccessConfig.IdleSettings idle = manager.getAccessConfig()
            .permissions().getFirst().idle().orElseThrow();
        assertEquals(6_000, idle.delayTicks());
        assertEquals(List.of("drink:default"), idle.emote());
        assertTrue(accessJson.contains("\"drink:default\""));
        assertEquals(1, manager.getConfig().schemaVersion());
        assertEquals(30, manager.getConfig().mineSkinCacheRetentionDays());
        assertEquals(256, manager.getConfig().mineSkinCacheMaxMiB());
        assertEquals(512, manager.getConfig().maxActiveDisplayEntities());
        String configJson = Files.readString(tempDir.resolve("emote").resolve("config.json"));
        assertTrue(configJson.contains("\"mineskin_cache_retention_days\": 30"));
        assertTrue(configJson.contains("\"mineskin_cache_max_mib\": 256"));
        assertTrue(configJson.contains("\"max_active_display_entities\": 512"));
        assertTrue(Files.isDirectory(manager.getAnimationDirectory()));
    }

    @Test
    void readsMineSkinCachePolicy(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = new ConfigManager(tempDir);
        manager.configure();
        Files.writeString(tempDir.resolve("emote").resolve("config.json"), """
            {
              "mineskin_cache_retention_days": 45,
              "mineskin_cache_max_mib": 512
            }
            """);

        assertTrue(manager.readConfig());
        assertEquals(45, manager.getConfig().mineSkinCacheRetentionDays());
        assertEquals(512, manager.getConfig().mineSkinCacheMaxMiB());
    }

    @Test
    void readsActiveDisplayEntityLimit(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = new ConfigManager(tempDir);
        manager.configure();
        Files.writeString(tempDir.resolve("emote").resolve("config.json"), """
            {"max_active_display_entities": 900}
            """);

        assertTrue(manager.readConfig());
        assertEquals(900, manager.getConfig().maxActiveDisplayEntities());
    }

    @Test
    void readsDisabledIdsAndPermissionGroups(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = new ConfigManager(tempDir);
        manager.configure();
        Files.writeString(tempDir.resolve("emote").resolve("emotes.json"), """
            {
              "schema_version": 2,
              "disabled": ["demo:wave"],
              "permissions": [
                {"permission":"emote.default","emotes":["demo:wave"]},
                {
                  "permission":"emote.vip",
                  "emotes":["*"],
                  "idle":{"delay":"600s","emote":["demo:wave","demo:sit"]}
                }
              ]
            }
            """);

        assertTrue(manager.readAccessConfig());
        assertFalse(manager.getAccessConfig().isEnabled("demo:wave"));
        assertTrue(manager.getAccessConfig().isEnabled("demo:bow"));
        assertEquals("emote.default", manager.getAccessConfig().permissions().getFirst().permission());
        assertEquals(List.of("demo:wave"), manager.getAccessConfig().permissions().getFirst().emotes());
        assertTrue(manager.getAccessConfig().permissions().getFirst().idle().isEmpty());
        AccessConfig.IdleSettings idle = manager.getAccessConfig().permissions().get(1).idle().orElseThrow();
        assertEquals(12_000, idle.delayTicks());
        assertEquals(List.of("demo:wave", "demo:sit"), idle.emote());
    }

    @Test
    void readsWeightedIdleEmotes(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = new ConfigManager(tempDir);
        manager.configure();
        Files.writeString(tempDir.resolve("emote").resolve("emotes.json"), """
            {
              "schema_version":2,
              "permissions":[
                {
                  "permission":"emote.default",
                  "emotes":["*"],
                  "idle":{"delay":"300s","emote":["demo:wave",30,"demo:sit",70]}
                }
              ]
            }
            """);

        assertTrue(manager.readAccessConfig());
        AccessConfig.IdleSettings idle = manager.getAccessConfig().permissions().getFirst().idle().orElseThrow();
        assertEquals(List.of("demo:wave", "demo:sit"), idle.emote());
        assertEquals(List.of(30, 70), idle.choices().stream().map(AccessConfig.IdleSettings.Choice::chance).toList());

        assertTrue(manager.setEmoteEnabled("demo:wave", false));
        assertTrue(manager.readAccessConfig());
        AccessConfig.IdleSettings rewrittenIdle = manager.getAccessConfig().permissions().getFirst().idle().orElseThrow();
        assertEquals(List.of(30, 70), rewrittenIdle.choices().stream().map(AccessConfig.IdleSettings.Choice::chance).toList());
    }

    @Test
    void rejectsWeightedIdleEmotesWhoseChancesDoNotTotalOneHundred(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = new ConfigManager(tempDir);
        manager.configure();
        Files.writeString(tempDir.resolve("emote").resolve("emotes.json"), """
            {
              "schema_version":2,
              "permissions":[
                {
                  "permission":"emote.default",
                  "emotes":["*"],
                  "idle":{"delay":"300s","emote":["demo:wave",30,"demo:sit",60]}
                }
              ]
            }
            """);

        assertFalse(manager.readAccessConfig());
        assertEquals(List.of("drink:default"), manager.getAccessConfig().permissions().getFirst().idle().orElseThrow().emote());
    }

    @Test
    void updatesDisabledIdsAndPreservesPermissions(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = new ConfigManager(tempDir);
        manager.configure();
        Files.writeString(tempDir.resolve("emote").resolve("emotes.json"), """
            {
              "schema_version":2,
              "disabled":[],
              "permissions":[
                {
                  "permission":"emote.vip",
                  "emotes":["demo:wave"],
                  "idle":{"delay":"300s","emote":["demo:wave"]}
                }
              ]
            }
            """);
        assertTrue(manager.readAccessConfig());

        assertTrue(manager.setEmoteEnabled("demo:wave", false));
        assertEquals(List.of("demo:wave"), manager.getAccessConfig().disabled());
        assertEquals(List.of("demo:wave"), manager.getAccessConfig().permissions().getFirst().emotes());
        assertTrue(manager.getAccessConfig().permissions().getFirst().idle().isPresent());
        assertTrue(Files.readString(tempDir.resolve("emote").resolve("emotes.json")).contains("demo:wave"));

        assertTrue(manager.setEmoteEnabled("demo:wave", true));
        assertTrue(manager.getAccessConfig().disabled().isEmpty());
    }

    @Test
    void rejectsBlankId(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = new ConfigManager(tempDir);
        manager.configure();
        Files.writeString(tempDir.resolve("emote").resolve("emotes.json"), """
            {"schema_version":2,"disabled":["   "]}
            """);

        assertFalse(manager.readAccessConfig());
        assertTrue(manager.getAccessConfig().disabled().isEmpty());
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
            {"schema_version":2,"disabled":{"invalid":true}}
            """);

        assertFalse(manager.readAccessConfig());
        assertTrue(manager.getAccessConfig().disabled().isEmpty());
    }

    @Test
    void rejectsUnsupportedAccessConfigSchema(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = new ConfigManager(tempDir);
        manager.configure();
        Files.writeString(tempDir.resolve("emote").resolve("emotes.json"), """
            {"schema_version":1,"disabled":[],"permissions":[]}
            """);

        assertFalse(manager.readAccessConfig());
        assertFalse(manager.getAccessConfig().permissions().isEmpty());
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
        ArrayList<AccessConfig.PermissionEntry> permissions = new ArrayList<>();
        permissions.add(new AccessConfig.PermissionEntry(
            "emote.default",
            List.of("demo:wave"),
            Optional.empty()
        ));
        AccessConfig config = new AccessConfig(disabled, permissions);

        disabled.clear();
        permissions.clear();

        assertFalse(config.isEnabled("demo:wave"));
        assertEquals(List.of("demo:wave"), config.disabled());
        assertEquals("emote.default", config.permissions().getFirst().permission());
        assertThrows(UnsupportedOperationException.class, () -> config.disabled().add("demo:bow"));
        assertThrows(UnsupportedOperationException.class, () -> config.permissions().add(
            new AccessConfig.PermissionEntry("vip", List.of("demo:bow"), Optional.empty())
        ));
    }

    @Test
    void rejectsInvalidIdleConfiguration(@TempDir Path tempDir) throws IOException {
        ConfigManager manager = new ConfigManager(tempDir);
        manager.configure();
        Files.writeString(tempDir.resolve("emote").resolve("emotes.json"), """
            {
              "schema_version":2,
              "permissions":[
                {
                  "permission":"emote.default",
                  "emotes":["*"],
                  "idle":{"delay":"0t","emote":["demo:wave"]}
                }
              ]
            }
            """);

        assertFalse(manager.readAccessConfig());
        assertEquals(
            List.of("drink:default"),
            manager.getAccessConfig().permissions().getFirst().idle().orElseThrow().emote()
        );
    }
}
