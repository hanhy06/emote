package io.github.hanhy06.emote.io;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JsonFileStoreTest {
    @Test
    void atomicWriteReplacesObjectAndRemovesTemporaryFile(@TempDir Path tempDir) throws IOException {
        Path filePath = tempDir.resolve("nested/cache.json");
        JsonObject first = new JsonObject();
        first.addProperty("value", 1);
        JsonObject second = new JsonObject();
        second.addProperty("value", 2);

        JsonFileStore.writeObjectAtomically(filePath, first, new Gson());
        JsonFileStore.writeObjectAtomically(filePath, second, new Gson());

        assertEquals(2, JsonFileStore.readObject(filePath).get("value").getAsInt());
        assertFalse(Files.exists(filePath.resolveSibling("cache.json.tmp")));
    }
}
