package io.github.hanhy06.emote.resource;

import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import net.minecraft.resources.Identifier;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class ResourcePackContributor {
    private static final String METADATA_FILE_NAME = "pack.mcmeta";
    private static final int MAX_ENTRY_COUNT = 65_536;
    private static final long MAX_EXPANDED_BYTES = 256L * 1024L * 1024L;

    int addTo(Path sourceDirectory, ResourcePackBuilder builder) throws IOException {
        Map<String, ResourceFile> resources = readResources(sourceDirectory);
        for (Map.Entry<String, ResourceFile> entry : resources.entrySet()) {
            if (!builder.addData(entry.getKey(), entry.getValue().data())) {
                throw new IOException("Polymer rejected resource " + entry.getKey());
            }
        }
        return resources.size();
    }

    private static Map<String, ResourceFile> readResources(Path sourceDirectory) throws IOException {
        Path metadataPath = sourceDirectory.resolve(METADATA_FILE_NAME);
        Budget budget = new Budget();
        Map<String, ResourceFile> resources = new TreeMap<>();
        try (var paths = Files.walk(sourceDirectory)) {
            for (Path sourcePath : paths.filter(Files::isRegularFile).sorted().toList()) {
                if (sourcePath.equals(metadataPath)) {
                    continue;
                }
                if (sourcePath.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
                    readZip(sourcePath, resources, budget);
                } else {
                    readLooseFile(sourcePath, resources, budget);
                }
            }
        }
        return resources;
    }

    private static void readLooseFile(Path sourcePath, Map<String, ResourceFile> resources, Budget budget) throws IOException {
        budget.addEntry();
        String resourcePath = decodeResourcePath(sourcePath.getFileName().toString());
        if (resourcePath == null) {
            return;
        }

        budget.addBytes(Files.size(sourcePath));
        addResource(resources, resourcePath, Files.readAllBytes(sourcePath), sourcePath.toString());
    }

    private static void readZip(Path zipPath, Map<String, ResourceFile> resources, Budget budget) throws IOException {
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }

                budget.addEntry();
                byte[] data = readEntry(input, budget);
                String entryName = fileName(entry.getName());
                if (entryName.equals(METADATA_FILE_NAME)) {
                    continue;
                }
                String resourcePath = decodeResourcePath(entryName);
                if (resourcePath == null) {
                    continue;
                }

                addResource(resources, resourcePath, data, zipPath + "!/" + entry.getName());
            }
        }
    }

    private static byte[] readEntry(InputStream input, Budget budget) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8_192];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            budget.addBytes(count);
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String decodeResourcePath(String fileName) throws IOException {
        if (fileName.indexOf(']') < 1) {
            return null;
        }

        String decoded = fileName.replace(']', '/');
        int namespaceEnd = decoded.indexOf('/');
        String namespace = decoded.substring(0, namespaceEnd);
        String path = decoded.substring(namespaceEnd + 1);
        Identifier id = Identifier.tryParse(namespace + ":" + path);
        if (id == null || !isGeneratedResourcePath(path)) {
            throw new IOException("Invalid flat resource filename: " + fileName);
        }
        return "assets/" + id.getNamespace() + "/" + id.getPath();
    }

    private static boolean isGeneratedResourcePath(String path) {
        if (path.startsWith("textures/")) {
            return path.endsWith(".png") || path.endsWith(".png.mcmeta");
        }
        return (path.startsWith("models/") || path.startsWith("items/")) && path.endsWith(".json");
    }

    private static String fileName(String path) {
        int separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return path.substring(separator + 1);
    }

    private static void addResource(
        Map<String, ResourceFile> resources,
        String resourcePath,
        byte[] data,
        String source
    ) throws IOException {
        ResourceFile existing = resources.putIfAbsent(resourcePath, new ResourceFile(data, source));
        if (existing != null && !Arrays.equals(existing.data(), data)) {
            throw new IOException(
                "Conflicting resource " + resourcePath + " from " + existing.source() + " and " + source
            );
        }
    }

    private record ResourceFile(byte[] data, String source) {
    }

    private static final class Budget {
        private int entryCount;
        private long expandedBytes;

        void addEntry() throws IOException {
            this.entryCount++;
            if (this.entryCount > MAX_ENTRY_COUNT) {
                throw new IOException("Resource inputs contain more than " + MAX_ENTRY_COUNT + " entries");
            }
        }

        void addBytes(long size) throws IOException {
            this.expandedBytes += size;
            if (this.expandedBytes > MAX_EXPANDED_BYTES) {
                throw new IOException("Resource inputs expand beyond " + MAX_EXPANDED_BYTES + " bytes");
            }
        }
    }
}
