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
    void scanReadsDirectoriesAndZipFilesInNameOrder(@TempDir Path tempDir) throws IOException {
        Path datapackDirectory = Files.createDirectories(tempDir.resolve("datapacks"));
        Path directoryPack = Files.createDirectories(datapackDirectory.resolve("beta"));
        Files.writeString(directoryPack.resolve("pack.mcmeta"), "{}");
        writeZip(datapackDirectory.resolve("alpha.zip"), "pack.mcmeta", "{}");
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
            (packPath, packRootPath) -> List.of(packPath.getFileName().toString())
        );

        assertEquals(List.of(), names);
    }

    private void writeZip(Path zipPath, String entryName, String content) throws IOException {
        try (OutputStream output = Files.newOutputStream(zipPath);
             ZipOutputStream zipOutput = new ZipOutputStream(output)) {
            zipOutput.putNextEntry(new ZipEntry(entryName));
            zipOutput.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zipOutput.closeEntry();
        }
    }
}
