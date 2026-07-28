package io.github.hanhy06.emote.config;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class JsonFileStore {
    private JsonFileStore() {
    }

    public static JsonObject readObject(Path filePath) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        }
    }

    public static void writeObjectAtomically(Path filePath, JsonObject object, Gson gson) throws IOException {
        Files.createDirectories(filePath.getParent());
        Path temporaryPath = filePath.resolveSibling(filePath.getFileName() + ".tmp");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(
                    temporaryPath,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            )) {
                gson.toJson(object, writer);
            }

            try {
                Files.move(temporaryPath, filePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryPath, filePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryPath);
        }
    }
}
