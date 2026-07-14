package io.github.hanhy06.emote.skin;

import com.google.gson.*;
import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.io.JsonFileStore;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

final class MineSkinTextureStore {
    private static final int CONTENT_CACHE_VERSION = 1;
    private final Path skinDirPath;
    private final Gson gson = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    MineSkinTextureStore() {
        this(null);
    }

    MineSkinTextureStore(Path skinDirPath) {
        this.skinDirPath = skinDirPath;
    }

    Map<PlayerSkinTextureKey, String> load(String textureHash, boolean slimModel) {
        Path filePath = resolveFilePath(textureHash, slimModel);
        if (filePath == null || !Files.exists(filePath)) {
            return Map.of();
        }

        try {
            JsonObject skinJson = JsonFileStore.readObject(filePath);
            if (skinJson == null) {
                return Map.of();
            }

            JsonArray textures = readTextures(skinJson);
            if (textures == null) {
                return Map.of();
            }

            Map<PlayerSkinTextureKey, String> textureUrlMap = new HashMap<>();
            for (JsonElement textureElement : textures) {
                PlayerSkinTextureKey textureKey = readTextureKey(textureElement);
                String textureUrl = readTextureUrl(textureElement);
                if (textureKey == null || textureUrl == null) {
                    continue;
                }

                textureUrlMap.put(textureKey, textureUrl);
            }

            return Map.copyOf(textureUrlMap);
        } catch (IOException | RuntimeException exception) {
            Emote.LOGGER.warn("Failed to read MineSkin texture store: {}", filePath, exception);
            return Map.of();
        }
    }

    void save(String textureHash, boolean slimModel, Map<PlayerSkinTextureKey, String> textureUrlMap) {
        if (textureUrlMap.isEmpty()) {
            return;
        }

        Path filePath = resolveFilePath(textureHash, slimModel);
        if (filePath == null) {
            return;
        }

        JsonObject skinJson = createSkinJson(textureHash, slimModel, textureUrlMap);
        try {
            JsonFileStore.writeObjectAtomically(filePath, skinJson, this.gson);
        } catch (IOException exception) {
            Emote.LOGGER.warn("Failed to write MineSkin texture store: {}", filePath, exception);
        }
    }

    MineSkinTextureResult loadContent(String contentHash) {
        Path filePath = resolveContentFilePath(contentHash);
        if (filePath == null || !Files.isRegularFile(filePath)) {
            return null;
        }

        try {
            JsonObject object = JsonFileStore.readObject(filePath);
            if (object == null) {
                return null;
            }

            Integer version = readInt(object, "version");
            String storedHash = readString(object, "content_hash");
            String textureUrl = readString(object, "texture_url");
            if (version == null || version != CONTENT_CACHE_VERSION || !contentHash.equals(storedHash) || textureUrl == null) {
                return null;
            }
            return new MineSkinTextureResult(textureUrl);
        } catch (IOException | RuntimeException exception) {
            Emote.LOGGER.warn("Failed to read MineSkin content cache: {}", filePath, exception);
            return null;
        }
    }

    void saveContent(String contentHash, MineSkinTextureResult result) {
        Path filePath = resolveContentFilePath(contentHash);
        if (filePath == null) {
            return;
        }

        JsonObject object = new JsonObject();
        object.addProperty("version", CONTENT_CACHE_VERSION);
        object.addProperty("content_hash", contentHash);
        object.addProperty("texture_url", result.textureUrl());
        try {
            JsonFileStore.writeObjectAtomically(filePath, object, this.gson);
        } catch (IOException exception) {
            Emote.LOGGER.warn("Failed to write MineSkin content cache: {}", filePath, exception);
        }
    }

    String loadPendingJob(String contentHash) {
        Path filePath = resolvePendingFilePath(contentHash);
        if (filePath == null || !Files.isRegularFile(filePath)) {
            return null;
        }
        try {
            JsonObject object = JsonFileStore.readObject(filePath);
            if (object == null) {
                return null;
            }
            return contentHash.equals(readString(object, "content_hash")) ? readString(object, "job_id") : null;
        } catch (IOException | RuntimeException exception) {
            Emote.LOGGER.warn("Failed to read MineSkin pending job: {}", filePath, exception);
            return null;
        }
    }

    void savePendingJob(String contentHash, String jobId) {
        Path filePath = resolvePendingFilePath(contentHash);
        if (filePath == null || jobId == null || jobId.isBlank()) {
            return;
        }
        JsonObject object = new JsonObject();
        object.addProperty("content_hash", contentHash);
        object.addProperty("job_id", jobId);
        try {
            JsonFileStore.writeObjectAtomically(filePath, object, this.gson);
        } catch (IOException exception) {
            Emote.LOGGER.warn("Failed to write MineSkin pending job: {}", filePath, exception);
        }
    }

    void clearPendingJob(String contentHash) {
        Path filePath = resolvePendingFilePath(contentHash);
        if (filePath == null) {
            return;
        }
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException exception) {
            Emote.LOGGER.warn("Failed to clear MineSkin pending job: {}", filePath, exception);
        }
    }

    private JsonObject createSkinJson(String textureHash, boolean slimModel, Map<PlayerSkinTextureKey, String> textureUrlMap) {
        JsonObject skinJson = new JsonObject();
        skinJson.addProperty("texture_hash", textureHash);
        skinJson.addProperty("slim_model", slimModel);

        JsonArray texturesJson = new JsonArray();
        List<Map.Entry<PlayerSkinTextureKey, String>> textureEntries = new ArrayList<>(textureUrlMap.entrySet());
        textureEntries.sort(Comparator
            .comparing((Map.Entry<PlayerSkinTextureKey, String> entry) -> entry.getKey().skinPart().ordinal())
            .thenComparing(entry -> entry.getKey().skinSegment().startY())
            .thenComparing(entry -> entry.getKey().skinSegment().endY()));

        for (Map.Entry<PlayerSkinTextureKey, String> textureEntry : textureEntries) {
            JsonObject textureJson = new JsonObject();
            textureJson.addProperty("skin_part", textureEntry.getKey().skinPart().id());
            textureJson.addProperty("segment_start_y", textureEntry.getKey().skinSegment().startY());
            textureJson.addProperty("segment_end_y", textureEntry.getKey().skinSegment().endY());
            textureJson.addProperty("texture_url", textureEntry.getValue());
            texturesJson.add(textureJson);
        }

        skinJson.add("textures", texturesJson);
        return skinJson;
    }

    private JsonArray readTextures(JsonObject skinJson) {
        JsonElement textures = skinJson.get("textures");
        return textures != null && textures.isJsonArray() ? textures.getAsJsonArray() : null;
    }

    private PlayerSkinTextureKey readTextureKey(JsonElement textureElement) {
        if (textureElement == null || !textureElement.isJsonObject()) {
            return null;
        }

        JsonObject textureJson = textureElement.getAsJsonObject();
        PlayerSkinPart skinPart = PlayerSkinPart.fromId(readString(textureJson, "skin_part"));
        Integer segmentStart = readInt(textureJson, "segment_start_y");
        Integer segmentEnd = readInt(textureJson, "segment_end_y");
        if (skinPart == null || segmentStart == null || segmentEnd == null) {
            return null;
        }

        try {
            return new PlayerSkinTextureKey(skinPart, new PlayerSkinSegment(segmentStart, segmentEnd));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String readTextureUrl(JsonElement textureElement) {
        if (textureElement == null || !textureElement.isJsonObject()) {
            return null;
        }

        return readString(textureElement.getAsJsonObject(), "texture_url");
    }

    private String readString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }

        String value = element.getAsString().trim();
        return value.isEmpty() ? null : value;
    }

    private Integer readInt(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }

        return element.getAsInt();
    }

    private Path resolveFilePath(String textureHash, boolean slimModel) {
        Path skinDirPath = resolveSkinDirPath();
        if (skinDirPath == null) {
            return null;
        }

        return skinDirPath.resolve(textureHash.toLowerCase(Locale.ROOT) + "-" + (slimModel ? "slim" : "classic") + ".json");
    }

    private Path resolveContentFilePath(String contentHash) {
        if (contentHash == null || !contentHash.matches("[0-9a-f]{64}")) {
            return null;
        }
        Path skinDirPath = resolveSkinDirPath();
        return skinDirPath == null ? null : skinDirPath.resolve("content").resolve(contentHash + ".json");
    }

    private Path resolvePendingFilePath(String contentHash) {
        if (contentHash == null || !contentHash.matches("[0-9a-f]{64}")) {
            return null;
        }
        Path skinDirPath = resolveSkinDirPath();
        return skinDirPath == null ? null : skinDirPath.resolve("pending").resolve(contentHash + ".json");
    }

    private Path resolveSkinDirPath() {
        if (this.skinDirPath != null) {
            return this.skinDirPath;
        }

        try {
            Path configDirPath = FabricLoader.getInstance().getConfigDir();
            if (configDirPath == null) {
                return null;
            }

            return configDirPath.resolve(Emote.MOD_ID).resolve("skin").resolve("mineskin");
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
