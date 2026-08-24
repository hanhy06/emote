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
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePackAssemblerTest {
    @Test
    void assemblesLooseAndZippedFlatResourcesUsingOnlyRootMetadata(@TempDir Path tempDir) throws Exception {
        Path sourceDirectory = tempDir.resolve("resource-pack");
        Path metadataPath = sourceDirectory.resolve("pack.mcmeta");
        Path looseTexture = sourceDirectory.resolve("anything/deep/textures/demo]textures]item]chair]body.png");
        Files.createDirectories(looseTexture.getParent());
        Files.writeString(metadataPath, "root metadata");
        Files.write(looseTexture, new byte[] {1, 2, 3});
        writeZip(sourceDirectory.resolve("downloads/site.resources.zip"), Map.of(
            "pack.mcmeta", new byte[] {9},
            "models/demo]models]item]chair]body.json", new byte[] {4},
            "models/demo]items]chair]body.json", new byte[] {5}
        ));

        Path outputFile = tempDir.resolve("generated/emote-resource-pack.zip");
        ResourcePackAssembler.BuildResult result = new ResourcePackAssembler().assemble(sourceDirectory, outputFile);
        Map<String, byte[]> entries = readZip(outputFile);

        assertEquals(3, result.resourceCount());
        assertTrue(result.archiveSize() > 0);
        assertEquals(40, result.sha1().length());
        assertArrayEquals("root metadata".getBytes(), entries.get("pack.mcmeta"));
        assertArrayEquals(new byte[] {1, 2, 3}, entries.get("assets/demo/textures/item/chair/body.png"));
        assertArrayEquals(new byte[] {4}, entries.get("assets/demo/models/item/chair/body.json"));
        assertArrayEquals(new byte[] {5}, entries.get("assets/demo/items/chair/body.json"));
        assertEquals(4, entries.size());

        assertEquals(result.sha1(), new ResourcePackAssembler().assemble(sourceDirectory, outputFile).sha1());
    }

    @Test
    void rejectsDifferentFilesForTheSameResourceWithoutReplacingThePreviousOutput(@TempDir Path tempDir) throws Exception {
        Path sourceDirectory = tempDir.resolve("resource-pack");
        Files.createDirectories(sourceDirectory.resolve("loose"));
        Files.writeString(sourceDirectory.resolve("pack.mcmeta"), "metadata");
        Files.write(sourceDirectory.resolve("loose/demo]textures]item]shared.png"), new byte[] {1});
        writeZip(sourceDirectory.resolve("conflict.zip"), Map.of(
            "demo]textures]item]shared.png", new byte[] {2}
        ));
        Path outputFile = tempDir.resolve("generated/emote-resource-pack.zip");
        Files.createDirectories(outputFile.getParent());
        Files.writeString(outputFile, "previous");

        IOException exception = assertThrows(
            IOException.class,
            () -> new ResourcePackAssembler().assemble(sourceDirectory, outputFile)
        );

        assertTrue(exception.getMessage().contains("Conflicting resource assets/demo/textures/item/shared.png"));
        assertEquals("previous", Files.readString(outputFile));
    }

    @Test
    void contributesDecodedResourcesToOnePolymerPack(@TempDir Path tempDir) throws Exception {
        Path sourceDirectory = tempDir.resolve("resource-pack");
        Files.createDirectories(sourceDirectory);
        Files.writeString(sourceDirectory.resolve("pack.mcmeta"), "source metadata");
        Files.write(sourceDirectory.resolve("demo]textures]item]chair.png"), new byte[] {1, 2, 3});

        Map<String, byte[]> entries = new HashMap<>();
        ResourcePackBuilder builder = (ResourcePackBuilder) Proxy.newProxyInstance(
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

        assertEquals(1, new ResourcePackAssembler().addTo(sourceDirectory, builder));
        assertArrayEquals(new byte[] {1, 2, 3}, entries.get("assets/demo/textures/item/chair.png"));
        assertEquals(1, entries.size());
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

    private static Map<String, byte[]> readZip(Path path) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipFile zip = new ZipFile(path.toFile())) {
            var iterator = zip.entries().asIterator();
            while (iterator.hasNext()) {
                ZipEntry entry = iterator.next();
                entries.put(entry.getName(), zip.getInputStream(entry).readAllBytes());
            }
        }
        return entries;
    }
}
