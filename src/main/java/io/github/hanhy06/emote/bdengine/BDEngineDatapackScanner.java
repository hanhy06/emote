package io.github.hanhy06.emote.bdengine;

import io.github.hanhy06.emote.Emote;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

final class BDEngineDatapackScanner {
    <T> List<T> scan(Path datapackDirectory, PackReader<T> reader) {
        if (!Files.isDirectory(datapackDirectory)) {
            return List.of();
        }

        List<T> results = new ArrayList<>();
        try (Stream<Path> packPaths = Files.list(datapackDirectory)) {
            for (Path packPath : packPaths.sorted(pathComparator()).toList()) {
                results.addAll(readPack(packPath, reader));
            }
        } catch (IOException exception) {
            Emote.LOGGER.warn("Failed to scan datapack directory {}", datapackDirectory, exception);
        }
        return List.copyOf(results);
    }

    List<Path> findNamespacePaths(Path packRootPath) {
        Path dataPath = packRootPath.resolve("data");
        if (!Files.isDirectory(dataPath)) {
            return List.of();
        }

        try (Stream<Path> namespacePaths = Files.list(dataPath)) {
            return namespacePaths
                .filter(Files::isDirectory)
                .sorted(pathComparator())
                .toList();
        } catch (IOException exception) {
            Emote.LOGGER.warn("Failed to scan datapack namespaces from {}", packRootPath, exception);
            return List.of();
        }
    }

    private <T> List<T> readPack(Path packPath, PackReader<T> reader) {
        if (Files.isDirectory(packPath)) {
            return reader.read(packPath, packPath);
        }

        String fileName = packPath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!Files.isRegularFile(packPath) || !fileName.endsWith(".zip")) {
            return List.of();
        }

        try (FileSystem fileSystem = FileSystems.newFileSystem(packPath, Map.of())) {
            return reader.read(packPath, fileSystem.getPath("/"));
        } catch (IOException exception) {
            Emote.LOGGER.warn("Failed to read zipped datapack {}", packPath, exception);
            return List.of();
        }
    }

    private Comparator<Path> pathComparator() {
        return Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT));
    }

    @FunctionalInterface
    interface PackReader<T> {
        List<T> read(Path packPath, Path packRootPath);
    }
}
