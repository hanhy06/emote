package io.github.hanhy06.emote.bdengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BDEngineDatapackScannerTest {
    @Test
    void findNamespacePathsReturnsDirectoriesInNameOrder(@TempDir Path tempDir) throws IOException {
        Path dataPath = Files.createDirectories(tempDir.resolve("data"));
        Files.createDirectories(dataPath.resolve("beta"));
        Files.createDirectories(dataPath.resolve("Alpha"));
        Files.writeString(dataPath.resolve("ignored.json"), "{}");

        List<String> namespaces = new BDEngineDatapackScanner().findNamespacePaths(tempDir).stream()
            .map(path -> path.getFileName().toString())
            .toList();

        assertEquals(List.of("Alpha", "beta"), namespaces);
    }

    @Test
    void scanReadsDirectoriesAndZipFilesInNameOrder(@TempDir Path tempDir) throws IOException {
        Path datapackDirectory = Files.createDirectories(tempDir.resolve("datapacks"));
        Path directoryPack = Files.createDirectories(datapackDirectory.resolve("beta"));
        Files.writeString(directoryPack.resolve("pack.mcmeta"), "{}");
        Files.createDirectories(datapackDirectory.resolve("not_a_pack"));
        writePackZip(datapackDirectory.resolve("alpha.zip"));
        Files.writeString(datapackDirectory.resolve("ignored.txt"), "{}");

        List<String> names = new BDEngineDatapackScanner().scan(
                datapackDirectory,
                (packPath, packRootPath) -> Files.exists(packRootPath.resolve("pack.mcmeta"))
                        ? List.of(packPath.getFileName().toString())
                        : List.of()
        );

        assertEquals(List.of("alpha.zip", "beta"), names);
    }

    @Test
    void scanReturnsEmptyForMissingDirectory(@TempDir Path tempDir) {
        List<String> names = new BDEngineDatapackScanner().scan(
                tempDir.resolve("missing"),
                (packPath, ignoredPackRootPath) -> List.of(packPath.getFileName().toString())
        );

        assertEquals(List.of(), names);
    }

    private void writePackZip(Path zipPath) throws IOException {
        try (OutputStream output = Files.newOutputStream(zipPath);
             ZipOutputStream zipOutput = new ZipOutputStream(output)) {
            zipOutput.putNextEntry(new ZipEntry("pack.mcmeta"));
            zipOutput.write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zipOutput.closeEntry();
        }
    }
}
