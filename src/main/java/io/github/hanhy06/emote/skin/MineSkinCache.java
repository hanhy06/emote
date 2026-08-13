package io.github.hanhy06.emote.skin;

import com.google.gson.*;
import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.config.JsonFileStore;
import io.github.hanhy06.emote.skin.model.PlayerSkinPart;
import io.github.hanhy06.emote.skin.model.PlayerSkinRegion;
import io.github.hanhy06.emote.skin.model.PlayerSkinSegment;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class MineSkinCache {
    private static final int CONTENT_CACHE_VERSION = 1;
    private static final int JOB_CACHE_VERSION = 1;
    private static final int SKIN_MEMORY_CACHE_MAX_ENTRIES = 1_024;
    private static final int CONTENT_MEMORY_CACHE_MAX_ENTRIES = 8_192;
    static final long PENDING_JOB_MAX_AGE_MILLIS = TimeUnit.MINUTES.toMillis(35);
    private static final long LAST_ACCESS_REFRESH_MILLIS = TimeUnit.DAYS.toMillis(1);
    private static final Pattern CONTENT_HASH_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private final Path skinDirPath;
    private final BoundedCache<SkinCacheKey, Map<PlayerSkinRegion, String>> skinTextures =
        new BoundedCache<>(SKIN_MEMORY_CACHE_MAX_ENTRIES);
    private final BoundedCache<String, String> contentTextureUrls =
        new BoundedCache<>(CONTENT_MEMORY_CACHE_MAX_ENTRIES);
    private final BoundedCache<Path, Long> refreshedAccessTimes =
        new BoundedCache<>(SKIN_MEMORY_CACHE_MAX_ENTRIES + CONTENT_MEMORY_CACHE_MAX_ENTRIES);
    private final Gson gson = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    MineSkinCache() {
        this(resolveDefaultSkinDirPath());
    }

    MineSkinCache(Path skinDirPath) {
        this.skinDirPath = Objects.requireNonNull(skinDirPath, "skinDirPath");
    }

    static String createContentKey(byte[] pngBytes, boolean slimModel) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((byte) (slimModel ? 1 : 0));
            digest.update(pngBytes);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    Map<PlayerSkinRegion, String> load(String textureHash, boolean slimModel) {
        SkinCacheKey cacheKey = new SkinCacheKey(textureHash, slimModel);
        Path filePath = resolveFilePath(textureHash, slimModel);
        Map<PlayerSkinRegion, String> cached = this.skinTextures.get(cacheKey);
        if (cached != null) {
            refreshLastUsed(filePath);
            return cached;
        }

        if (!Files.exists(filePath)) {
            return cacheSkinTextures(cacheKey, Map.of());
        }

        try {
            JsonObject skinJson = JsonFileStore.readObject(filePath);
            if (skinJson == null) {
                return cacheSkinTextures(cacheKey, Map.of());
            }

            JsonArray textures = readTextures(skinJson);
            if (textures == null) {
                return cacheSkinTextures(cacheKey, Map.of());
            }

            Map<PlayerSkinRegion, String> textureUrlMap = new HashMap<>();
            for (JsonElement textureElement : textures) {
                PlayerSkinRegion textureKey = readTextureKey(textureElement);
                String textureUrl = readTextureUrl(textureElement);
                if (textureKey == null || textureUrl == null) {
                    continue;
                }

                textureUrlMap.put(textureKey, textureUrl);
            }

            if (textureUrlMap.isEmpty()) {
                return cacheSkinTextures(cacheKey, Map.of());
            }
            refreshLastUsed(filePath);
            return cacheSkinTextures(cacheKey, Map.copyOf(textureUrlMap));
        } catch (IOException | RuntimeException exception) {
            Emote.LOGGER.warn("Failed to read MineSkin texture store: {}", filePath, exception);
            return cacheSkinTextures(cacheKey, Map.of());
        }
    }

    void save(String textureHash, boolean slimModel, Map<PlayerSkinRegion, String> textureUrlMap) {
        if (textureUrlMap.isEmpty()) {
            return;
        }

        Path filePath = resolveFilePath(textureHash, slimModel);
        Map<PlayerSkinRegion, String> savedTextureUrls = Map.copyOf(textureUrlMap);
        JsonObject skinJson = createSkinJson(textureHash, slimModel, savedTextureUrls);
        try {
            JsonFileStore.writeObjectAtomically(filePath, skinJson, this.gson);
            this.skinTextures.put(new SkinCacheKey(textureHash, slimModel), savedTextureUrls);
            this.refreshedAccessTimes.put(filePath, System.currentTimeMillis());
        } catch (IOException exception) {
            Emote.LOGGER.warn("Failed to write MineSkin texture store: {}", filePath, exception);
        }
    }

    String loadContent(String contentHash) {
        Path filePath = resolveCacheFilePath(contentHash, "content");
        String cachedTextureUrl = this.contentTextureUrls.get(contentHash);
        if (cachedTextureUrl != null) {
            refreshLastUsed(filePath);
            return cachedTextureUrl;
        }

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
            refreshLastUsed(filePath);
            return existingTextureUrl == null ? textureUrl : existingTextureUrl;
        } catch (IOException | RuntimeException exception) {
            Emote.LOGGER.warn("Failed to read MineSkin content cache: {}", filePath, exception);
            return null;
        }
    }

    void saveContent(String contentHash, String textureUrl) {
        Path filePath = resolveCacheFilePath(contentHash, "content");
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
            this.refreshedAccessTimes.put(filePath, System.currentTimeMillis());
        } catch (IOException exception) {
            Emote.LOGGER.warn("Failed to write MineSkin content cache: {}", filePath, exception);
        }
    }

    MineSkinPendingJob loadPendingJob(String contentHash) {
        Path filePath = resolveCacheFilePath(contentHash, "pending");
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
        Path filePath = resolveCacheFilePath(contentHash, "pending");
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
        deleteCacheFile(contentHash, "pending", "pending job");
    }

    MineSkinFailure loadFailure(String contentHash, long nowEpochMillis) {
        Path filePath = resolveCacheFilePath(contentHash, "failures");
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
        Path filePath = resolveCacheFilePath(contentHash, "failures");
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
        deleteCacheFile(contentHash, "failures", "failure state");
    }

    private void deleteCacheFile(String contentHash, String directoryName, String description) {
        Path filePath = resolveCacheFilePath(contentHash, directoryName);
        if (filePath == null) {
            return;
        }
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException exception) {
            Emote.LOGGER.warn("Failed to clear MineSkin {}: {}", description, filePath, exception);
        }
    }

    void clearMemory() {
        this.skinTextures.clear();
        this.contentTextureUrls.clear();
        this.refreshedAccessTimes.clear();
    }

    CleanupResult cleanup(long retentionMillis, long maximumBytes, long nowEpochMillis) {
        if (retentionMillis < 1L) {
            throw new IllegalArgumentException("retentionMillis must be positive");
        }
        if (maximumBytes < 1L) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        if (!Files.isDirectory(this.skinDirPath)) {
            return new CleanupResult(0, 0, 0, 0L);
        }

        int transientFilesDeleted = cleanupTransientFiles(nowEpochMillis);
        List<CacheFile> cacheFiles = listCacheFiles();
        long retentionCutoff = nowEpochMillis - retentionMillis;
        int expiredFilesDeleted = 0;
        long retainedBytes = 0L;
        List<CacheFile> retainedFiles = new ArrayList<>(cacheFiles.size());
        for (CacheFile cacheFile : cacheFiles) {
            if (cacheFile.lastModifiedMillis() < retentionCutoff && deleteFile(cacheFile.path())) {
                expiredFilesDeleted++;
                continue;
            }
            retainedFiles.add(cacheFile);
            retainedBytes += cacheFile.sizeBytes();
        }

        int capacityFilesDeleted = 0;
        if (retainedBytes > maximumBytes) {
            retainedFiles.sort(Comparator.comparingLong(CacheFile::lastModifiedMillis));
            for (CacheFile cacheFile : retainedFiles) {
                if (retainedBytes <= maximumBytes) {
                    break;
                }
                if (deleteFile(cacheFile.path())) {
                    retainedBytes -= cacheFile.sizeBytes();
                    capacityFilesDeleted++;
                }
            }
        }

        if (expiredFilesDeleted > 0 || capacityFilesDeleted > 0) {
            clearMemory();
        }
        return new CleanupResult(
            expiredFilesDeleted,
            capacityFilesDeleted,
            transientFilesDeleted,
            Math.max(0L, retainedBytes)
        );
    }

    private Map<PlayerSkinRegion, String> cacheSkinTextures(
        SkinCacheKey cacheKey,
        Map<PlayerSkinRegion, String> textureUrls
    ) {
        Map<PlayerSkinRegion, String> existing = this.skinTextures.putIfAbsent(cacheKey, textureUrls);
        return existing == null ? textureUrls : existing;
    }

    private void refreshLastUsed(Path filePath) {
        long now = System.currentTimeMillis();
        Long refreshedAt = this.refreshedAccessTimes.get(filePath);
        if (refreshedAt != null && now - refreshedAt < LAST_ACCESS_REFRESH_MILLIS) {
            return;
        }
        try {
            FileTime lastModified = Files.getLastModifiedTime(filePath);
            if (now - lastModified.toMillis() >= LAST_ACCESS_REFRESH_MILLIS) {
                Files.setLastModifiedTime(filePath, FileTime.fromMillis(now));
            }
            this.refreshedAccessTimes.put(filePath, now);
        } catch (IOException ignored) {
        }
    }

    private int cleanupTransientFiles(long nowEpochMillis) {
        int deleted = 0;
        Path pendingDirectory = this.skinDirPath.resolve("pending");
        for (Path filePath : listJsonFiles(pendingDirectory)) {
            Long submittedAt = readLongFromFile(filePath, "submitted_at");
            if ((submittedAt == null || nowEpochMillis - submittedAt > PENDING_JOB_MAX_AGE_MILLIS)
                && deleteFile(filePath)) {
                deleted++;
            }
        }

        Path failureDirectory = this.skinDirPath.resolve("failures");
        for (Path filePath : listJsonFiles(failureDirectory)) {
            Long retryAfter = readLongFromFile(filePath, "retry_after");
            if ((retryAfter == null || retryAfter <= nowEpochMillis) && deleteFile(filePath)) {
                deleted++;
            }
        }
        return deleted;
    }

    private List<CacheFile> listCacheFiles() {
        List<CacheFile> result = new ArrayList<>();
        for (Path filePath : listJsonFiles(this.skinDirPath)) {
            String fileName = filePath.getFileName().toString();
            if (fileName.endsWith("-classic.json") || fileName.endsWith("-slim.json")) {
                addCacheFile(result, filePath);
            }
        }
        for (Path filePath : listJsonFiles(this.skinDirPath.resolve("content"))) {
            String fileName = filePath.getFileName().toString();
            String contentHash = fileName.substring(0, fileName.length() - ".json".length());
            if (isContentHash(contentHash)) {
                addCacheFile(result, filePath);
            }
        }
        return result;
    }

    private void addCacheFile(List<CacheFile> result, Path filePath) {
        try {
            result.add(new CacheFile(
                filePath,
                Files.size(filePath),
                Files.getLastModifiedTime(filePath).toMillis()
            ));
        } catch (IOException exception) {
            Emote.LOGGER.warn("Failed to inspect MineSkin cache file: {}", filePath, exception);
        }
    }

    private List<Path> listJsonFiles(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(directory)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".json"))
                .toList();
        } catch (IOException exception) {
            Emote.LOGGER.warn("Failed to list MineSkin cache directory: {}", directory, exception);
            return List.of();
        }
    }

    private Long readLongFromFile(Path filePath, String fieldName) {
        try {
            JsonObject object = JsonFileStore.readObject(filePath);
            return object == null ? null : readLong(object, fieldName);
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private boolean deleteFile(Path filePath) {
        try {
            return Files.deleteIfExists(filePath);
        } catch (IOException exception) {
            Emote.LOGGER.warn("Failed to delete MineSkin cache file: {}", filePath, exception);
            return false;
        }
    }

    private JsonObject createSkinJson(String textureHash, boolean slimModel, Map<PlayerSkinRegion, String> textureUrlMap) {
        JsonObject skinJson = new JsonObject();
        skinJson.addProperty("texture_hash", textureHash);
        skinJson.addProperty("slim_model", slimModel);

        JsonArray texturesJson = new JsonArray();
        List<Map.Entry<PlayerSkinRegion, String>> textureEntries = new ArrayList<>(textureUrlMap.entrySet());
        textureEntries.sort(Comparator
            .comparing((Map.Entry<PlayerSkinRegion, String> entry) -> entry.getKey().skinPart().ordinal())
            .thenComparing(entry -> entry.getKey().skinSegment().startY())
            .thenComparing(entry -> entry.getKey().skinSegment().endY()));

        for (Map.Entry<PlayerSkinRegion, String> textureEntry : textureEntries) {
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

    private PlayerSkinRegion readTextureKey(JsonElement textureElement) {
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
            return new PlayerSkinRegion(skinPart, new PlayerSkinSegment(segmentStart, segmentEnd));
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
        return this.skinDirPath.resolve(
            textureHash.toLowerCase(Locale.ROOT) + "-" + (slimModel ? "slim" : "classic") + ".json"
        );
    }

    private Path resolveCacheFilePath(String contentHash, String directoryName) {
        if (!isContentHash(contentHash)) {
            return null;
        }
        return this.skinDirPath.resolve(directoryName).resolve(contentHash + ".json");
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

    record CleanupResult(
        int expiredFilesDeleted,
        int capacityFilesDeleted,
        int transientFilesDeleted,
        long retainedBytes
    ) {
        int totalFilesDeleted() {
            return this.expiredFilesDeleted + this.capacityFilesDeleted + this.transientFilesDeleted;
        }
    }

    private record CacheFile(Path path, long sizeBytes, long lastModifiedMillis) {
    }

    private static boolean isContentHash(String contentHash) {
        return contentHash != null && CONTENT_HASH_PATTERN.matcher(contentHash).matches();
    }

    private static Path resolveDefaultSkinDirPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(Emote.MOD_ID).resolve("skin").resolve("mineskin");
    }
}
