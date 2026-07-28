package io.github.hanhy06.emote.skin;

import com.google.gson.*;
import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.config.JsonFileStore;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

final class MineSkinCache {
    private static final int CONTENT_CACHE_VERSION = 1;
    private static final int JOB_CACHE_VERSION = 1;
    private static final int SKIN_MEMORY_CACHE_MAX_ENTRIES = 1_024;
    private static final int CONTENT_MEMORY_CACHE_MAX_ENTRIES = 8_192;
    private final Path skinDirPath;
    private final BoundedCache<SkinCacheKey, Map<PlayerSkinTextureKey, String>> skinTextures =
        new BoundedCache<>(SKIN_MEMORY_CACHE_MAX_ENTRIES);
    private final BoundedCache<String, String> contentTextureUrls =
        new BoundedCache<>(CONTENT_MEMORY_CACHE_MAX_ENTRIES);
    private final Gson gson = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    MineSkinCache() {
        this(null);
    }

    MineSkinCache(Path skinDirPath) {
        this.skinDirPath = skinDirPath;
    }

    static String createContentKey(byte[] pngBytes, boolean slimModel) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((byte)(slimModel ? 1 : 0));
            digest.update(pngBytes);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    Map<PlayerSkinTextureKey, String> load(String textureHash, boolean slimModel) {
        SkinCacheKey cacheKey = new SkinCacheKey(textureHash, slimModel);
        Map<PlayerSkinTextureKey, String> cached = this.skinTextures.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        Path filePath = resolveFilePath(textureHash, slimModel);
        if (filePath == null) {
            return Map.of();
        }
        if (!Files.exists(filePath)) {
            return cacheSkinTextures(cacheKey, Map.of());
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

            if (textureUrlMap.isEmpty()) {
                return Map.of();
            }
            return cacheSkinTextures(cacheKey, Map.copyOf(textureUrlMap));
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

        Map<PlayerSkinTextureKey, String> savedTextureUrls = Map.copyOf(textureUrlMap);
        JsonObject skinJson = createSkinJson(textureHash, slimModel, savedTextureUrls);
        try {
            JsonFileStore.writeObjectAtomically(filePath, skinJson, this.gson);
            this.skinTextures.put(new SkinCacheKey(textureHash, slimModel), savedTextureUrls);
        } catch (IOException exception) {
            Emote.LOGGER.warn("Failed to write MineSkin texture store: {}", filePath, exception);
        }
    }

    String loadContent(String contentHash) {
        String cachedTextureUrl = this.contentTextureUrls.get(contentHash);
        if (cachedTextureUrl != null) {
            return cachedTextureUrl;
        }

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
            String existingTextureUrl = this.contentTextureUrls.putIfAbsent(contentHash, textureUrl);
            return existingTextureUrl == null ? textureUrl : existingTextureUrl;
        } catch (IOException | RuntimeException exception) {
            Emote.LOGGER.warn("Failed to read MineSkin content cache: {}", filePath, exception);
            return null;
        }
    }

    void saveContent(String contentHash, String textureUrl) {
        Path filePath = resolveContentFilePath(contentHash);
        if (filePath == null) {
            return;
        }

        JsonObject object = new JsonObject();
        object.addProperty("version", CONTENT_CACHE_VERSION);
        object.addProperty("content_hash", contentHash);
        object.addProperty("texture_url", textureUrl);
        try {
            JsonFileStore.writeObjectAtomically(filePath, object, this.gson);
            this.contentTextureUrls.put(contentHash, textureUrl);
        } catch (IOException exception) {
            Emote.LOGGER.warn("Failed to write MineSkin content cache: {}", filePath, exception);
        }
    }

    MineSkinPendingJob loadPendingJob(String contentHash) {
        Path filePath = resolvePendingFilePath(contentHash);
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
            String jobId = readString(object, "job_id");
            Long submittedAt = readLong(object, "submitted_at");
            if (version == null || version != JOB_CACHE_VERSION || !contentHash.equals(storedHash)
                || jobId == null || submittedAt == null || submittedAt <= 0L) {
                clearPendingJob(contentHash);
                return null;
            }
            return new MineSkinPendingJob(jobId, submittedAt);
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
        object.addProperty("version", JOB_CACHE_VERSION);
        object.addProperty("content_hash", contentHash);
        object.addProperty("job_id", jobId);
        object.addProperty("submitted_at", System.currentTimeMillis());
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

    boolean isRetryBlocked(String contentHash, long nowEpochMillis) {
        MineSkinFailure failure = loadFailure(contentHash, nowEpochMillis);
        return failure != null;
    }

    MineSkinFailure loadFailure(String contentHash, long nowEpochMillis) {
        Path filePath = resolveFailureFilePath(contentHash);
        if (filePath == null || !Files.isRegularFile(filePath)) {
            return null;
        }
        try {
            JsonObject object = JsonFileStore.readObject(filePath);
            Long retryAfter = object == null ? null : readLong(object, "retry_after");
            if (retryAfter == null || retryAfter <= nowEpochMillis) {
                Files.deleteIfExists(filePath);
                return null;
            }
            String errorMessage = readString(object, "last_error");
            return new MineSkinFailure(retryAfter, errorMessage == null ? "MineSkin request failed" : errorMessage);
        } catch (IOException | RuntimeException exception) {
            Emote.LOGGER.warn("Failed to read MineSkin failure state: {}", filePath, exception);
            return null;
        }
    }

    void saveFailure(String contentHash, String errorMessage, long retryAfterEpochMillis) {
        Path filePath = resolveFailureFilePath(contentHash);
        if (filePath == null) {
            return;
        }
        JsonObject object = new JsonObject();
        object.addProperty("version", JOB_CACHE_VERSION);
        object.addProperty("content_hash", contentHash);
        object.addProperty("retry_after", retryAfterEpochMillis);
        object.addProperty("last_error", errorMessage);
        try {
            JsonFileStore.writeObjectAtomically(filePath, object, this.gson);
        } catch (IOException exception) {
            Emote.LOGGER.warn("Failed to write MineSkin failure state: {}", filePath, exception);
        }
    }

    void clearFailure(String contentHash) {
        Path filePath = resolveFailureFilePath(contentHash);
        if (filePath == null) {
            return;
        }
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException exception) {
            Emote.LOGGER.warn("Failed to clear MineSkin failure state: {}", filePath, exception);
        }
    }

    void clearMemory() {
        this.skinTextures.clear();
        this.contentTextureUrls.clear();
    }

    private Map<PlayerSkinTextureKey, String> cacheSkinTextures(
        SkinCacheKey cacheKey,
        Map<PlayerSkinTextureKey, String> textureUrls
    ) {
        Map<PlayerSkinTextureKey, String> existing = this.skinTextures.putIfAbsent(cacheKey, textureUrls);
        return existing == null ? textureUrls : existing;
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

    private Long readLong(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return element.getAsLong();
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

    private Path resolveFailureFilePath(String contentHash) {
        if (contentHash == null || !contentHash.matches("[0-9a-f]{64}")) {
            return null;
        }
        Path skinDirPath = resolveSkinDirPath();
        return skinDirPath == null ? null : skinDirPath.resolve("failures").resolve(contentHash + ".json");
    }

    record MineSkinPendingJob(String jobId, long submittedAtEpochMillis) {
        MineSkinPendingJob {
            Objects.requireNonNull(jobId, "jobId");
        }
    }

    private record SkinCacheKey(String textureHash, boolean slimModel) {
        private SkinCacheKey {
            textureHash = Objects.requireNonNull(textureHash, "textureHash").toLowerCase(Locale.ROOT);
        }
    }

    private static final class BoundedCache<K, V> {
        private final int maximumSize;
        private final LinkedHashMap<K, V> values = new LinkedHashMap<>(16, 0.75F, true);

        private BoundedCache(int maximumSize) {
            this.maximumSize = maximumSize;
        }

        private synchronized V get(K key) {
            return this.values.get(key);
        }

        private synchronized void put(K key, V value) {
            this.values.put(key, value);
            trimToMaximumSize();
        }

        private synchronized V putIfAbsent(K key, V value) {
            V existing = this.values.get(key);
            if (existing != null) {
                return existing;
            }
            this.values.put(key, value);
            trimToMaximumSize();
            return null;
        }

        private synchronized void clear() {
            this.values.clear();
        }

        private void trimToMaximumSize() {
            while (this.values.size() > this.maximumSize) {
                this.values.pollFirstEntry();
            }
        }
    }

    record MineSkinFailure(long retryAfterEpochMillis, String errorMessage) {
        MineSkinFailure {
            Objects.requireNonNull(errorMessage, "errorMessage");
        }
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
