package io.github.hanhy06.emote.skin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MineSkinCacheTest {
    @Test
    void saveAndLoadRoundTrip(@TempDir Path tempDir) {
        MineSkinCache store = new MineSkinCache(tempDir);
        Map<PlayerSkinTextureKey, String> savedTextureUrls = Map.of(
            new PlayerSkinTextureKey(PlayerSkinPart.HEAD, PlayerSkinSegment.FULL), "https://textures.minecraft.net/texture/head",
            new PlayerSkinTextureKey(PlayerSkinPart.LEFT_ARM, new PlayerSkinSegment(2, 8)), "https://textures.minecraft.net/texture/left_arm"
        );

        store.save("ABCDEF", true, savedTextureUrls);

        assertEquals(savedTextureUrls, store.load("abcdef", true));
    }

    @Test
    void contentCacheRoundTrip(@TempDir Path tempDir) {
        MineSkinCache store = new MineSkinCache(tempDir);
        String contentHash = MineSkinCache.createContentKey(new byte[]{1, 2, 3}, false);
        String result = "https://textures.minecraft.net/texture/shared";

        store.saveContent(contentHash, result);

        assertEquals(result, store.loadContent(contentHash));
    }

    @Test
    void repeatedSkinLoadUsesMemoryCache(@TempDir Path tempDir) throws IOException {
        PlayerSkinTextureKey textureKey = new PlayerSkinTextureKey(PlayerSkinPart.HEAD, PlayerSkinSegment.FULL);
        Map<PlayerSkinTextureKey, String> saved = Map.of(textureKey, "https://textures.example/head");
        new MineSkinCache(tempDir).save("ABCDEF", false, saved);
        MineSkinCache store = new MineSkinCache(tempDir);

        assertEquals(saved, store.load("abcdef", false));
        Files.delete(tempDir.resolve("abcdef-classic.json"));

        assertEquals(saved, store.load("ABCDEF", false));
        store.clearMemory();
        assertTrue(store.load("abcdef", false).isEmpty());
    }

    @Test
    void repeatedMissingSkinLoadUsesMemoryCache(@TempDir Path tempDir) {
        MineSkinCache store = new MineSkinCache(tempDir);
        PlayerSkinTextureKey textureKey = new PlayerSkinTextureKey(PlayerSkinPart.HEAD, PlayerSkinSegment.FULL);
        Map<PlayerSkinTextureKey, String> saved = Map.of(textureKey, "https://textures.example/head");

        assertTrue(store.load("abcdef", false).isEmpty());
        new MineSkinCache(tempDir).save("abcdef", false, saved);

        assertTrue(store.load("abcdef", false).isEmpty());
        store.clearMemory();
        assertEquals(saved, store.load("abcdef", false));
    }

    @Test
    void repeatedContentLoadUsesMemoryCache(@TempDir Path tempDir) throws IOException {
        String contentHash = MineSkinCache.createContentKey(new byte[]{4, 5, 6}, true);
        String textureUrl = "https://textures.example/shared";
        new MineSkinCache(tempDir).saveContent(contentHash, textureUrl);
        MineSkinCache store = new MineSkinCache(tempDir);

        assertEquals(textureUrl, store.loadContent(contentHash));
        Files.delete(tempDir.resolve("content").resolve(contentHash + ".json"));

        assertEquals(textureUrl, store.loadContent(contentHash));
        store.clearMemory();
        assertNull(store.loadContent(contentHash));
    }

    @Test
    void contentKeyIncludesModelVariant() {
        byte[] image = new byte[]{4, 5, 6};

        assertNotEquals(MineSkinCache.createContentKey(image, false), MineSkinCache.createContentKey(image, true));
    }

    @Test
    void rejectsInvalidContentHash(@TempDir Path tempDir) {
        MineSkinCache store = new MineSkinCache(tempDir);

        assertNull(store.loadContent("../invalid"));
    }

    @Test
    void pendingJobRoundTrip(@TempDir Path tempDir) {
        MineSkinCache store = new MineSkinCache(tempDir);
        String contentHash = MineSkinCache.createContentKey(new byte[]{7, 8, 9}, true);

        store.savePendingJob(contentHash, "job-123");
        MineSkinCache.MineSkinPendingJob pendingJob = store.loadPendingJob(contentHash);
        assertNotNull(pendingJob);
        assertEquals("job-123", pendingJob.jobId());
        assertTrue(pendingJob.submittedAtEpochMillis() > 0L);

        store.clearPendingJob(contentHash);
        assertNull(store.loadPendingJob(contentHash));
    }

    @Test
    void failureBlocksRetryUntilCooldownExpires(@TempDir Path tempDir) {
        MineSkinCache store = new MineSkinCache(tempDir);
        String contentHash = MineSkinCache.createContentKey(new byte[]{10, 11, 12}, false);

        store.saveFailure(contentHash, "failed", 2_000L);

        assertTrue(store.isRetryBlocked(contentHash, 1_999L));
        MineSkinCache.MineSkinFailure failure = store.loadFailure(contentHash, 1_999L);
        assertNotNull(failure);
        assertEquals(2_000L, failure.retryAfterEpochMillis());
        assertEquals("failed", failure.errorMessage());
        assertFalse(store.isRetryBlocked(contentHash, 2_000L));
    }
}
