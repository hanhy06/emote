package io.github.hanhy06.emote.skin;

import com.google.gson.*;
import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.config.JsonFileStore;
import io.github.hanhy06.emote.skin.model.PlayerSkinPart;
import io.github.hanhy06.emote.skin.model.PlayerSkinRegion;
import io.github.hanhy06.emote.skin.model.PlayerSkinSource;
import io.github.hanhy06.emote.util.Sha256;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class SkinCache {
    private static final int BAKE_VERSION = 1;
    private static final int CONTENT_CACHE_VERSION = 1;
    private static final int JOB_CACHE_VERSION = 1;
    private static final int SKIN_MEMORY_CACHE_MAX_ENTRIES = 1_024;
    private static final int CONTENT_MEMORY_CACHE_MAX_ENTRIES = 8_192;
    public static final long PENDING_JOB_MAX_AGE_MILLIS = TimeUnit.MINUTES.toMillis(35);
    private static final long LAST_ACCESS_REFRESH_MILLIS = TimeUnit.DAYS.toMillis(1);
    private static final Pattern CONTENT_HASH_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private final Path skinDirPath;
    private final Object memoryLock = new Object();
    private long memoryEpoch;
    private final BoundedCache<PlayerSkinSource.Key, Map<PlayerSkinRegion, String>> skinTextures =
        new BoundedCache<>(SKIN_MEMORY_CACHE_MAX_ENTRIES);
    private final BoundedCache<String, String> contentTextureUrls =
        new BoundedCache<>(CONTENT_MEMORY_CACHE_MAX_ENTRIES);
    private final BoundedCache<Path, Long> refreshedAccessTimes =
        new BoundedCache<>(SKIN_MEMORY_CACHE_MAX_ENTRIES + CONTENT_MEMORY_CACHE_MAX_ENTRIES);
    private final Gson gson = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    public SkinCache() {
        this(resolveDefaultSkinDirPath());
    }

    public SkinCache(Path skinDirPath) {
        this.skinDirPath = Objects.requireNonNull(skinDirPath, "skinDirPath");
    }

    public static String createContentKey(byte[] pngBytes, boolean slimModel) {
        MessageDigest digest = Sha256.newDigest();
        digest.update((byte) (slimModel ? 1 : 0));
        digest.update(pngBytes);
        return HexFormat.of().formatHex(digest.digest());
    }

    long epoch() { synchronized (this.memoryLock) { return this.memoryEpoch; } }

    public Map<PlayerSkinRegion, String> peek(String textureHash, boolean slimModel) {
        synchronized (this.memoryLock) {
            return this.skinTextures.get(new PlayerSkinSource.Key(textureHash.toLowerCase(Locale.ROOT), slimModel));
        }
    }

    boolean markUsed(String textureHash, boolean slimModel) {
        Path path = resolveFilePath(textureHash, slimModel);
        long now = System.currentTimeMillis();
        synchronized (this.memoryLock) {
            Long previous = this.refreshedAccessTimes.get(path);
            if (previous != null && now - previous < LAST_ACCESS_REFRESH_MILLIS) return false;
            this.refreshedAccessTimes.put(path, now);
            return true;
        }
    }

    public synchronized void touch(String textureHash, boolean slimModel, long expectedEpoch) {
        if (expectedEpoch == epoch()) touchFile(resolveFilePath(textureHash, slimModel), expectedEpoch);
    }

    public synchronized Map<PlayerSkinRegion, String> load(String textureHash, boolean slimModel, long expectedEpoch) {
        if (expectedEpoch != epoch()) return Map.of();
        PlayerSkinSource.Key cacheKey = new PlayerSkinSource.Key(Objects.requireNonNull(textureHash, "textureHash").toLowerCase(Locale.ROOT), slimModel);
        Path filePath = resolveFilePath(textureHash, slimModel);
        Map<PlayerSkinRegion, String> cached;
        synchronized (this.memoryLock) {
            cached = this.skinTextures.get(cacheKey);
        }
        if (cached != null) {
            refreshLastUsed(filePath, expectedEpoch);
            return cached;
        }

        if (!Files.exists(filePath)) {
            return cacheSkinTextures(cacheKey, Map.of(), expectedEpoch);
        }

        try {
            JsonObject skinJson = JsonFileStore.readObject(filePath);
            if (skinJson == null || !Objects.equals(readInt(skinJson, "bake_version"), BAKE_VERSION)) {
                return cacheSkinTextures(cacheKey, Map.of(), expectedEpoch);
            }

            JsonElement textures = skinJson.get("textures");
            if (textures == null || !textures.isJsonArray()) {
                return cacheSkinTextures(cacheKey, Map.of(), expectedEpoch);
            }

            Map<PlayerSkinRegion, String> textureUrlMap = new HashMap<>();
            for (JsonElement textureElement : textures.getAsJsonArray()) {
                if (!textureElement.isJsonObject()) continue;
                PlayerSkinRegion region = readTextureRegion(textureElement);
                String textureUrl = readString(textureElement.getAsJsonObject(), "texture_url");
                if (region == null || textureUrl == null) {
                    continue;
                }

                textureUrlMap.put(region, textureUrl);
            }

            if (textureUrlMap.isEmpty()) {
                return cacheSkinTextures(cacheKey, Map.of(), expectedEpoch);
            }
            refreshLastUsed(filePath, expectedEpoch);
            return cacheSkinTextures(cacheKey, Map.copyOf(textureUrlMap), expectedEpoch);
        } catch (IOException | RuntimeException exception) {
            EmoteMod.LOGGER.warn("Failed to read skin texture cache: {}", filePath, exception);
            return cacheSkinTextures(cacheKey, Map.of(), expectedEpoch);
        }
    }

    public synchronized boolean save(String textureHash, boolean slimModel, Map<PlayerSkinRegion, String> textureUrlMap, long expectedEpoch) {
        if (expectedEpoch != epoch()) return false;
        if (textureUrlMap.isEmpty()) {
            return true;
        }

        Path filePath = resolveFilePath(textureHash, slimModel);
        Map<PlayerSkinRegion, String> merged = new HashMap<>(load(textureHash, slimModel, expectedEpoch));
        merged.putAll(textureUrlMap);
        Map<PlayerSkinRegion, String> savedTextureUrls = Map.copyOf(merged);
        JsonObject skinJson = createSkinJson(textureHash, slimModel, savedTextureUrls);
        try {
            JsonFileStore.writeObjectAtomically(filePath, skinJson, this.gson);
            synchronized (this.memoryLock) {
                if (expectedEpoch != this.memoryEpoch) return false;
                this.skinTextures.put(new PlayerSkinSource.Key(textureHash.toLowerCase(Locale.ROOT), slimModel), savedTextureUrls);
                this.refreshedAccessTimes.put(filePath, System.currentTimeMillis());
            }
            return true;
        } catch (IOException exception) {
            EmoteMod.LOGGER.warn("Failed to write skin texture cache: {}", filePath, exception);
            return false;
        }
    }

    public synchronized String loadContent(String contentHash, long expectedEpoch) {
        if (expectedEpoch != epoch()) return null;
        Path filePath = resolveCacheFilePath(contentHash, "content");
        String cachedTextureUrl;
        synchronized (this.memoryLock) { cachedTextureUrl = this.contentTextureUrls.get(contentHash); }
        if (cachedTextureUrl != null) {
            refreshLastUsed(filePath, expectedEpoch);
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
            String existingTextureUrl;
            synchronized (this.memoryLock) {
                if (expectedEpoch != this.memoryEpoch) return null;
                existingTextureUrl = this.contentTextureUrls.putIfAbsent(contentHash, textureUrl);
            }
            refreshLastUsed(filePath, expectedEpoch);
            return existingTextureUrl == null ? textureUrl : existingTextureUrl;
        } catch (IOException | RuntimeException exception) {
            EmoteMod.LOGGER.warn("Failed to read skin content cache: {}", filePath, exception);
            return null;
        }
    }

    public synchronized boolean saveContent(String contentHash, String textureUrl, long expectedEpoch) {
        if (expectedEpoch != epoch()) return false;
        Path filePath = resolveCacheFilePath(contentHash, "content");
        if (filePath == null) {
            return false;
        }

        JsonObject object = new JsonObject();
        object.addProperty("version", CONTENT_CACHE_VERSION);
        object.addProperty("content_hash", contentHash);
        object.addProperty("texture_url", textureUrl);
        try {
            JsonFileStore.writeObjectAtomically(filePath, object, this.gson);
            synchronized (this.memoryLock) {
                if (expectedEpoch != this.memoryEpoch) return false;
                this.contentTextureUrls.put(contentHash, textureUrl);
                this.refreshedAccessTimes.put(filePath, System.currentTimeMillis());
            }
            clearPendingJob(contentHash);
            clearFailure(contentHash);
            return true;
        } catch (IOException exception) {
            EmoteMod.LOGGER.warn("Failed to write skin content cache: {}", filePath, exception);
            return false;
        }
    }

    public synchronized PendingJob loadPendingJob(String contentHash) {
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
            return new PendingJob(jobId, submittedAt);
        } catch (IOException | RuntimeException exception) {
            EmoteMod.LOGGER.warn("Failed to read pending skin upload: {}", filePath, exception);
            return null;
        }
    }

    public synchronized void savePendingJob(String contentHash, String jobId) {
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
            EmoteMod.LOGGER.warn("Failed to write pending skin upload: {}", filePath, exception);
        }
    }

    public synchronized void clearPendingJob(String contentHash) {
        deleteCacheFile(contentHash, "pending", "pending job");
    }

    public synchronized Failure loadFailure(String contentHash, long nowEpochMillis) {
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
            return new Failure(retryAfter, errorMessage == null ? "Skin upload request failed" : errorMessage);
        } catch (IOException | RuntimeException exception) {
            EmoteMod.LOGGER.warn("Failed to read skin upload failure state: {}", filePath, exception);
            return null;
        }
    }

    public synchronized void saveFailure(String contentHash, String errorMessage, long retryAfterEpochMillis) {
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
            EmoteMod.LOGGER.warn("Failed to write skin upload failure state: {}", filePath, exception);
        }
    }

    public synchronized void clearFailure(String contentHash) {
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
            EmoteMod.LOGGER.warn("Failed to clear skin cache {}: {}", description, filePath, exception);
        }
    }

    public void clearMemory() {
        synchronized (this.memoryLock) {
            this.memoryEpoch++;
            this.skinTextures.clear();
            this.contentTextureUrls.clear();
            this.refreshedAccessTimes.clear();
        }
    }

    public synchronized CleanupResult cleanup(long retentionMillis, long maximumBytes, long nowEpochMillis, long expectedEpoch) {
        if (expectedEpoch != epoch()) return new CleanupResult(0, 0, 0, 0L);
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
            synchronized (this.memoryLock) {
                if (expectedEpoch == this.memoryEpoch) {
                    this.skinTextures.clear();
                    this.contentTextureUrls.clear();
                    this.refreshedAccessTimes.clear();
                }
            }
        }
        return new CleanupResult(
            expiredFilesDeleted,
            capacityFilesDeleted,
            transientFilesDeleted,
            Math.max(0L, retainedBytes)
        );
    }

    private Map<PlayerSkinRegion, String> cacheSkinTextures(
        PlayerSkinSource.Key cacheKey,
        Map<PlayerSkinRegion, String> textureUrls,
        long expectedEpoch
    ) {
        synchronized (this.memoryLock) {
            if (expectedEpoch != this.memoryEpoch) return Map.of();
            Map<PlayerSkinRegion, String> existing = this.skinTextures.putIfAbsent(cacheKey, textureUrls);
            return existing == null ? textureUrls : existing;
        }
    }

    private void refreshLastUsed(Path filePath, long expectedEpoch) {
        long now = System.currentTimeMillis();
        synchronized (this.memoryLock) {
            if (expectedEpoch != this.memoryEpoch) return;
            Long refreshedAt = this.refreshedAccessTimes.get(filePath);
            if (refreshedAt != null && now - refreshedAt < LAST_ACCESS_REFRESH_MILLIS) return;
        }
        touchFile(filePath, expectedEpoch);
    }

    private void touchFile(Path filePath, long expectedEpoch) {
        long now = System.currentTimeMillis();
        try {
            FileTime lastModified = Files.getLastModifiedTime(filePath);
            if (now - lastModified.toMillis() >= LAST_ACCESS_REFRESH_MILLIS) {
                Files.setLastModifiedTime(filePath, FileTime.fromMillis(now));
            }
            synchronized (this.memoryLock) {
                if (expectedEpoch == this.memoryEpoch) this.refreshedAccessTimes.put(filePath, now);
            }
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
            long lastUsed = Files.getLastModifiedTime(filePath).toMillis();
            synchronized (this.memoryLock) {
                Long refreshedAt = this.refreshedAccessTimes.get(filePath);
                if (refreshedAt != null) lastUsed = Math.max(lastUsed, refreshedAt);
            }
            result.add(new CacheFile(
                filePath,
                Files.size(filePath),
                lastUsed
            ));
        } catch (IOException exception) {
            EmoteMod.LOGGER.warn("Failed to inspect skin cache file: {}", filePath, exception);
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
            EmoteMod.LOGGER.warn("Failed to list skin cache directory: {}", directory, exception);
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
            EmoteMod.LOGGER.warn("Failed to delete skin cache file: {}", filePath, exception);
            return false;
        }
    }

    private JsonObject createSkinJson(String textureHash, boolean slimModel, Map<PlayerSkinRegion, String> textureUrlMap) {
        JsonObject skinJson = new JsonObject();
        skinJson.addProperty("bake_version", BAKE_VERSION);
        skinJson.addProperty("texture_hash", textureHash);
        skinJson.addProperty("slim_model", slimModel);

        JsonArray texturesJson = new JsonArray();
        List<Map.Entry<PlayerSkinRegion, String>> textureEntries = new ArrayList<>(textureUrlMap.entrySet());
        textureEntries.sort(Comparator
            .comparing((Map.Entry<PlayerSkinRegion, String> entry) -> entry.getKey().part().ordinal())
            .thenComparing(entry -> entry.getKey().from())
            .thenComparing(entry -> entry.getKey().to()));

        for (Map.Entry<PlayerSkinRegion, String> textureEntry : textureEntries) {
            JsonObject textureJson = new JsonObject();
            textureJson.addProperty("skin_part", textureEntry.getKey().part().id());
            textureJson.addProperty("region_from", textureEntry.getKey().from());
            textureJson.addProperty("region_to", textureEntry.getKey().to());
            textureJson.addProperty("texture_url", textureEntry.getValue());
            texturesJson.add(textureJson);
        }

        skinJson.add("textures", texturesJson);
        return skinJson;
    }

    private PlayerSkinRegion readTextureRegion(JsonElement textureElement) {
        if (textureElement == null || !textureElement.isJsonObject()) {
            return null;
        }

        JsonObject textureJson = textureElement.getAsJsonObject();
        PlayerSkinPart skinPart = PlayerSkinPart.fromId(readString(textureJson, "skin_part"));
        Double segmentStart = readDouble(textureJson, "region_from");
        Double segmentEnd = readDouble(textureJson, "region_to");
        if (skinPart == null || segmentStart == null || segmentEnd == null) {
            return null;
        }

        try {
            return new PlayerSkinRegion(skinPart, segmentStart, segmentEnd);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String readString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }

        String value = element.getAsString().trim();
        return value.isEmpty() ? null : value;
    }

    private Double readDouble(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? null : element.getAsDouble();
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

    public record PendingJob(String jobId, long submittedAtEpochMillis) {
        public PendingJob {
            Objects.requireNonNull(jobId, "jobId");
        }
    }


    private static final class BoundedCache<K, V> {
        private final int maximumSize;
        private final LinkedHashMap<K, V> values = new LinkedHashMap<>(16, 0.75F, true);

        private BoundedCache(int maximumSize) {
            this.maximumSize = maximumSize;
        }

        private V get(K key) {
            return this.values.get(key);
        }

        private void put(K key, V value) {
            this.values.put(key, value);
            trimToMaximumSize();
        }

        private V putIfAbsent(K key, V value) {
            V existing = this.values.get(key);
            if (existing != null) {
                return existing;
            }
            this.values.put(key, value);
            trimToMaximumSize();
            return null;
        }

        private void clear() {
            this.values.clear();
        }

        private void trimToMaximumSize() {
            while (this.values.size() > this.maximumSize) {
                this.values.pollFirstEntry();
            }
        }
    }

    public record Failure(long retryAfterEpochMillis, String errorMessage) {
        public Failure {
            Objects.requireNonNull(errorMessage, "errorMessage");
        }
    }

    public record CleanupResult(
        int expiredFilesDeleted,
        int capacityFilesDeleted,
        int transientFilesDeleted,
        long retainedBytes
    ) {
        public int totalFilesDeleted() {
            return this.expiredFilesDeleted + this.capacityFilesDeleted + this.transientFilesDeleted;
        }
    }

    private record CacheFile(Path path, long sizeBytes, long lastModifiedMillis) {
    }

    private static boolean isContentHash(String contentHash) {
        return contentHash != null && CONTENT_HASH_PATTERN.matcher(contentHash).matches();
    }

    private static Path resolveDefaultSkinDirPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(EmoteMod.MOD_ID).resolve("skin");
    }
}
