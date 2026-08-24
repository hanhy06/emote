package io.github.hanhy06.emote.resource;

import io.github.hanhy06.emote.config.ConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePackServiceTest {
    @Test
    void createsTheHiddenGeneratedPackWhenExplicitlyRebuilt(@TempDir Path tempDir) throws Exception {
        ConfigManager configManager = new ConfigManager(tempDir);
        configManager.configure();

        new ResourcePackService(configManager).rebuild();

        assertTrue(Files.isRegularFile(configManager.getResourcePackDirectory().resolve("pack.mcmeta")));
        assertTrue(Files.isRegularFile(tempDir.resolve("emote/.resource-pack.zip")));
    }
}
