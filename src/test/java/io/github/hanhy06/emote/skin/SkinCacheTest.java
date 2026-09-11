package io.github.hanhy06.emote.skin;

import io.github.hanhy06.emote.skin.model.PlayerSkinPart;
import io.github.hanhy06.emote.skin.model.PlayerSkinRegion;
import io.github.hanhy06.emote.skin.model.PlayerSkinSegment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SkinCacheTest {
    @Test
    void saveAndLoadRoundTrip(@TempDir Path tempDir) {
        SkinCache store = new SkinCache(tempDir);
        Map<PlayerSkinRegion, String> savedTextureUrls = Map.of(
            new PlayerSkinRegion(PlayerSkinPart.HEAD, PlayerSkinSegment.FULL), "https://textures.minecraft.net/texture/head",
            new PlayerSkinRegion(PlayerSkinPart.LEFT_ARM, new PlayerSkinSegment(2, 8)), "https://textures.minecraft.net/texture/left_arm"
        );

        store.save("ABCDEF", true, savedTextureUrls);

        assertEquals(savedTextureUrls, store.load("abcdef", true));
    }

    @Test
    void contentCacheRoundTrip(@TempDir Path tempDir) {
        SkinCache store = new SkinCache(tempDir);
        String contentHash = SkinCache.createContentKey(new byte[] {1, 2, 3}, false);
        String result = "https://textures.minecraft.net/texture/shared";

        store.saveContent(contentHash, result);

        assertEquals(result, store.loadContent(contentHash));
    }

    @Test
    void repeatedSkinLoadUsesMemoryCache(@TempDir Path tempDir) throws IOException {
        PlayerSkinRegion textureKey = new PlayerSkinRegion(PlayerSkinPart.HEAD, PlayerSkinSegment.FULL);
        Map<PlayerSkinRegion, String> saved = Map.of(textureKey, "https://textures.example/head");
        new SkinCache(tempDir).save("ABCDEF", false, saved);
        SkinCache store = new SkinCache(tempDir);

        assertEquals(saved, store.load("abcdef", false));
        Files.delete(tempDir.resolve("abcdef-classic.json"));

        assertEquals(saved, store.load("ABCDEF", false));
        store.clearMemory();
        assertTrue(store.load("abcdef", false).isEmpty());
    }

    @Test
    void repeatedMissingSkinLoadUsesMemoryCache(@TempDir Path tempDir) {
        SkinCache store = new SkinCache(tempDir);
        PlayerSkinRegion textureKey = new PlayerSkinRegion(PlayerSkinPart.HEAD, PlayerSkinSegment.FULL);
        Map<PlayerSkinRegion, String> saved = Map.of(textureKey, "https://textures.example/head");

        assertTrue(store.load("abcdef", false).isEmpty());
        new SkinCache(tempDir).save("abcdef", false, saved);

        assertTrue(store.load("abcdef", false).isEmpty());
        store.clearMemory();
        assertEquals(saved, store.load("abcdef", false));
    }

    @Test
    void repeatedContentLoadUsesMemoryCache(@TempDir Path tempDir) throws IOException {
        String contentHash = SkinCache.createContentKey(new byte[] {4, 5, 6}, true);
        String textureUrl = "https://textures.example/shared";
        new SkinCache(tempDir).saveContent(contentHash, textureUrl);
        SkinCache store = new SkinCache(tempDir);

        assertEquals(textureUrl, store.loadContent(contentHash));
        Files.delete(tempDir.resolve("content").resolve(contentHash + ".json"));

        assertEquals(textureUrl, store.loadContent(contentHash));
        store.clearMemory();
        assertNull(store.loadContent(contentHash));
    }

    @Test
    void contentKeyIncludesModelVariant() {
        byte[] image = new byte[] {4, 5, 6};

        assertNotEquals(SkinCache.createContentKey(image, false), SkinCache.createContentKey(image, true));
    }

    @Test
    void rejectsInvalidContentHash(@TempDir Path tempDir) {
        SkinCache store = new SkinCache(tempDir);

        assertNull(store.loadContent("../invalid"));
    }

    @Test
    void pendingJobRoundTrip(@TempDir Path tempDir) {
        SkinCache store = new SkinCache(tempDir);
        String contentHash = SkinCache.createContentKey(new byte[] {7, 8, 9}, true);

        store.savePendingJob(contentHash, "job-123");
        SkinCache.PendingJob pendingJob = store.loadPendingJob(contentHash);
        assertNotNull(pendingJob);
        assertEquals("job-123", pendingJob.jobId());
        assertTrue(pendingJob.submittedAtEpochMillis() > 0L);

        store.clearPendingJob(contentHash);
        assertNull(store.loadPendingJob(contentHash));
    }

    @Test
    void failureBlocksRetryUntilCooldownExpires(@TempDir Path tempDir) {
        SkinCache store = new SkinCache(tempDir);
        String contentHash = SkinCache.createContentKey(new byte[] {10, 11, 12}, false);

        store.saveFailure(contentHash, "failed", 2_000L);

        SkinCache.Failure failure = store.loadFailure(contentHash, 1_999L);
        assertNotNull(failure);
        assertEquals(2_000L, failure.retryAfterEpochMillis());
        assertEquals("failed", failure.errorMessage());
        assertNull(store.loadFailure(contentHash, 2_000L));
    }

    @Test
    void cleanupRemovesExpiredCacheAndRetainsRecentlyUsedEntry(@TempDir Path tempDir) throws IOException {
        SkinCache store = new SkinCache(tempDir);
        PlayerSkinRegion textureKey = new PlayerSkinRegion(PlayerSkinPart.HEAD, PlayerSkinSegment.FULL);
        Path expiredPath = tempDir.resolve("expired-classic.json");
        store.save("expired", false, Map.of(textureKey, "https://textures.example/expired"));

        String usedContentHash = SkinCache.createContentKey(new byte[] {13, 14, 15}, false);
        Path usedContentPath = tempDir.resolve("content").resolve(usedContentHash + ".json");
        store.saveContent(usedContentHash, "https://textures.example/used");

        long now = System.currentTimeMillis();
        FileTime oldTime = FileTime.fromMillis(now - TimeUnit.DAYS.toMillis(31));
        Files.setLastModifiedTime(expiredPath, oldTime);
        Files.setLastModifiedTime(usedContentPath, oldTime);
        store.clearMemory();

        assertEquals("https://textures.example/used", store.loadContent(usedContentHash));
        SkinCache.CleanupResult result = store.cleanup(
            TimeUnit.DAYS.toMillis(30),
            256L * 1_024L * 1_024L,
            System.currentTimeMillis()
        );

        assertEquals(1, result.expiredFilesDeleted());
        assertFalse(Files.exists(expiredPath));
        assertTrue(Files.exists(usedContentPath));
    }

    @Test
    void cleanupEvictsOldestFilesUntilUnderCapacity(@TempDir Path tempDir) throws IOException {
        SkinCache store = new SkinCache(tempDir);
        String oldestHash = SkinCache.createContentKey(new byte[] {16}, false);
        String newestHash = SkinCache.createContentKey(new byte[] {17}, false);
        Path oldestPath = tempDir.resolve("content").resolve(oldestHash + ".json");
        Path newestPath = tempDir.resolve("content").resolve(newestHash + ".json");
        store.saveContent(oldestHash, "https://textures.example/oldest");
        store.saveContent(newestHash, "https://textures.example/newest");

        long now = System.currentTimeMillis();
        Files.setLastModifiedTime(oldestPath, FileTime.fromMillis(now - TimeUnit.DAYS.toMillis(2)));
        Files.setLastModifiedTime(newestPath, FileTime.fromMillis(now - TimeUnit.DAYS.toMillis(1)));
        long maximumBytes = Files.size(newestPath);

        SkinCache.CleanupResult result = store.cleanup(
            TimeUnit.DAYS.toMillis(30),
            maximumBytes,
            now
        );

        assertEquals(1, result.capacityFilesDeleted());
        assertFalse(Files.exists(oldestPath));
        assertTrue(Files.exists(newestPath));
        assertTrue(result.retainedBytes() <= maximumBytes);
    }

    @Test
    void cleanupRemovesAbandonedPendingAndExpiredFailureFiles(@TempDir Path tempDir) throws IOException {
        SkinCache store = new SkinCache(tempDir);
        String pendingHash = SkinCache.createContentKey(new byte[] {18}, false);
        String failureHash = SkinCache.createContentKey(new byte[] {19}, false);
        store.savePendingJob(pendingHash, "job-abandoned");
        store.saveFailure(failureHash, "retry later", 1_000L);

        Path pendingPath = tempDir.resolve("pending").resolve(pendingHash + ".json");
        Files.writeString(pendingPath, """
            {
              "version": 1,
              "content_hash": "%s",
              "job_id": "job-abandoned",
              "submitted_at": 1
            }
            """.formatted(pendingHash));

        SkinCache.CleanupResult result = store.cleanup(
            TimeUnit.DAYS.toMillis(30),
            256L * 1_024L * 1_024L,
            SkinCache.PENDING_JOB_MAX_AGE_MILLIS + 2_000L
        );

        assertEquals(2, result.transientFilesDeleted());
        assertFalse(Files.exists(pendingPath));
        assertFalse(Files.exists(tempDir.resolve("failures").resolve(failureHash + ".json")));
    }
}
