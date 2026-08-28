package io.github.hanhy06.emote.resource;

import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ResourcePackContributorTest {
    @Test
    void snapshotsOnlyMeaningfulResourceInputs(@TempDir Path tempDir) throws Exception {
        Path sourceDirectory = tempDir.resolve("resource-pack");
        Files.createDirectories(sourceDirectory);
        Files.writeString(sourceDirectory.resolve("pack.mcmeta"), "metadata");

        ResourcePackContributor contributor = new ResourcePackContributor();
        ResourcePackContributor.Snapshot empty = contributor.read(sourceDirectory);
        assertTrue(empty.isEmpty());

        Files.write(sourceDirectory.resolve("demo]textures]item]chair.png"), new byte[] {1});

        ResourcePackContributor.Snapshot populated = contributor.read(sourceDirectory);
        assertFalse(populated.isEmpty());
        assertFalse(populated.hasSameContent(empty));
    }

    @Test
    void contributesLooseAndZippedFlatResources(@TempDir Path tempDir) throws Exception {
        Path sourceDirectory = tempDir.resolve("resource-pack");
        Path looseTexture = sourceDirectory.resolve("anything/deep/textures/demo]textures]item]chair]body.png");
        Files.createDirectories(looseTexture.getParent());
        Files.writeString(sourceDirectory.resolve("pack.mcmeta"), "root metadata");
        Files.write(looseTexture, new byte[] {1, 2, 3});
        writeZip(sourceDirectory.resolve("downloads/site.resources.zip"), Map.of(
            "pack.mcmeta", new byte[] {9},
            "models/demo]models]item]chair]body.json", new byte[] {4},
            "models/demo]items]chair]body.json", new byte[] {5}
        ));

        Map<String, byte[]> entries = new HashMap<>();
        ResourcePackContributor contributor = new ResourcePackContributor();
        int resourceCount = contributor.addTo(contributor.read(sourceDirectory), builder(entries));

        assertEquals(3, resourceCount);
        assertArrayEquals(new byte[] {1, 2, 3}, entries.get("assets/demo/textures/item/chair/body.png"));
        assertArrayEquals(new byte[] {4}, entries.get("assets/demo/models/item/chair/body.json"));
        assertArrayEquals(new byte[] {5}, entries.get("assets/demo/items/chair/body.json"));
        assertEquals(3, entries.size());
    }

    @Test
    void rejectsDifferentFilesForTheSameResource(@TempDir Path tempDir) throws Exception {
        Path sourceDirectory = tempDir.resolve("resource-pack");
        Files.createDirectories(sourceDirectory.resolve("loose"));
        Files.write(sourceDirectory.resolve("loose/demo]textures]item]shared.png"), new byte[] {1});
        writeZip(sourceDirectory.resolve("conflict.zip"), Map.of(
            "demo]textures]item]shared.png", new byte[] {2}
        ));

        IOException exception = assertThrows(
            IOException.class,
            () -> {
                ResourcePackContributor contributor = new ResourcePackContributor();
                contributor.addTo(contributor.read(sourceDirectory), builder(new HashMap<>()));
            }
        );

        assertTrue(exception.getMessage().contains("Conflicting resource assets/demo/textures/item/shared.png"));
    }

    @Test
    void treatsRepackedZipWithTheSameResourcesAsUnchanged(@TempDir Path tempDir) throws Exception {
        Path sourceDirectory = tempDir.resolve("resource-pack");
        Path zipPath = sourceDirectory.resolve("resources.zip");
        ResourcePackContributor contributor = new ResourcePackContributor();

        writeZip(zipPath, Map.of(
            "first/demo]models]item]chair.json", new byte[] {1, 2},
            "second/demo]textures]item]chair.png", new byte[] {3, 4}
        ));
        ResourcePackContributor.Snapshot first = contributor.read(sourceDirectory);

        writeZip(zipPath, Map.of(
            "moved/demo]textures]item]chair.png", new byte[] {3, 4},
            "moved/demo]models]item]chair.json", new byte[] {1, 2}
        ));
        ResourcePackContributor.Snapshot repacked = contributor.read(sourceDirectory);

        assertTrue(repacked.hasSameContent(first));
    }

    @Test
    void detectsChangedResourceContent(@TempDir Path tempDir) throws Exception {
        Path sourceDirectory = tempDir.resolve("resource-pack");
        Path resource = sourceDirectory.resolve("demo]textures]item]chair.png");
        Files.createDirectories(sourceDirectory);
        ResourcePackContributor contributor = new ResourcePackContributor();

        Files.write(resource, new byte[] {1, 2});
        ResourcePackContributor.Snapshot first = contributor.read(sourceDirectory);
        Files.write(resource, new byte[] {1, 3});
        ResourcePackContributor.Snapshot changed = contributor.read(sourceDirectory);

        assertFalse(changed.hasSameContent(first));
    }

    private static ResourcePackBuilder builder(Map<String, byte[]> entries) {
        return (ResourcePackBuilder) Proxy.newProxyInstance(
            ResourcePackBuilder.class.getClassLoader(),
            new Class<?>[] {ResourcePackBuilder.class},
            (ignoredProxy, method, arguments) -> {
                if (method.getName().equals("addData") && arguments[1] instanceof byte[] data) {
                    entries.put((String) arguments[0], data);
                    return true;
                }
                throw new UnsupportedOperationException(method.toString());
            }
        );
    }

    private static void writeZip(Path path, Map<String, byte[]> entries) throws IOException {
        Files.createDirectories(path.getParent());
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }
}
