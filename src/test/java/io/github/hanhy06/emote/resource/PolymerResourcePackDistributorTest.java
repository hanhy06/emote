package io.github.hanhy06.emote.resource;

import io.github.hanhy06.emote.config.ConfigManager;
import eu.pb4.polymer.resourcepack.api.OutputGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolymerResourcePackDistributorTest {
    @Test
    void buildsIndependentPackAndKeepsItWhenNextBuildFails(@TempDir Path tempDir) throws IOException {
        ConfigManager configManager = new ConfigManager(tempDir);
        configManager.configureResourcePack();
        Files.write(
            configManager.getResourcePackDirectory().resolve("example]textures]emote.png"),
            new byte[]{1, 2, 3}
        );
        Path outputPath = tempDir.resolve("published/emote.zip");
        int[] buildCount = {0};
        PolymerResourcePackDistributor distributor = new PolymerResourcePackDistributor(
            configManager,
            outputPath,
            false,
            (snapshot, stagingPath) -> {
                buildCount[0]++;
                if (buildCount[0] > 1) {
                    return null;
                }
                Files.write(stagingPath, new byte[]{9, 8, 7});
                return new OutputGenerator.Result(stagingPath, "first", false);
            }
        );

        assertEquals(PolymerResourcePackDistributor.BuildResult.BUILT, distributor.rebuild());
        assertTrue(Files.isRegularFile(outputPath));
        byte[] published = Files.readAllBytes(outputPath);

        Files.write(
            configManager.getResourcePackDirectory().resolve("example]textures]second.png"),
            new byte[]{4, 5, 6}
        );

        assertEquals(PolymerResourcePackDistributor.BuildResult.FAILED, distributor.rebuild());
        assertArrayEquals(published, Files.readAllBytes(outputPath));
    }

    @Test
    void skipsBuildWhenIndependentPackInputsAreUnchanged(@TempDir Path tempDir) throws IOException {
        ConfigManager configManager = new ConfigManager(tempDir);
        configManager.configureResourcePack();
        Files.write(
            configManager.getResourcePackDirectory().resolve("example]models]emote.json"),
            "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        int[] buildCount = {0};
        PolymerResourcePackDistributor distributor = new PolymerResourcePackDistributor(
            configManager,
            tempDir.resolve("published/emote.zip"),
            false,
            (snapshot, stagingPath) -> {
                buildCount[0]++;
                Files.write(stagingPath, new byte[]{1});
                return new OutputGenerator.Result(stagingPath, "unchanged", false);
            }
        );

        assertEquals(PolymerResourcePackDistributor.BuildResult.BUILT, distributor.rebuild());
        assertEquals(PolymerResourcePackDistributor.BuildResult.UNCHANGED, distributor.rebuild());
        assertEquals(1, buildCount[0]);
    }
}
