package io.github.hanhy06.emote.skin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.hanhy06.emote.Emote;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MineSkinTextureStore {
    private final Path skinDirPath;
    private final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    public MineSkinTextureStore() {
        this(null);
    }

    MineSkinTextureStore(Path skinDirPath) {
        this.skinDirPath = skinDirPath;
    }

    public Map<PlayerSkinTextureKey, String> load(String textureHash, boolean slimModel) {
        Path filePath = resolveFilePath(textureHash, slimModel);
        if (filePath == null || !Files.exists(filePath)) {
            return Map.of();
        }

        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (!element.isJsonObject()) {
                return Map.of();
            }

            JsonArray textures = readTextures(element.getAsJsonObject());
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

    public void save(String textureHash, boolean slimModel, Map<PlayerSkinTextureKey, String> textureUrlMap) {
        if (textureUrlMap.isEmpty()) {
            return;
        }

        Path skinDirPath = resolveSkinDirPath();
        if (skinDirPath == null) {
            return;
        }

        try {
            Files.createDirectories(skinDirPath);
        } catch (IOException exception) {
            Emote.LOGGER.warn("Failed to create MineSkin skin directory: {}", skinDirPath, exception);
            return;
        }

        Path filePath = resolveFilePath(textureHash, slimModel);
        if (filePath == null) {
            return;
        }

        JsonObject skinJson = createSkinJson(textureHash, slimModel, textureUrlMap);
        try (BufferedWriter writer = Files.newBufferedWriter(
                filePath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        )) {
            this.gson.toJson(skinJson, writer);
        } catch (IOException exception) {
            Emote.LOGGER.warn("Failed to write MineSkin texture store: {}", filePath, exception);
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
