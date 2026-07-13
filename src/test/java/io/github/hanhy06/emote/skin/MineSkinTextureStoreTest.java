package io.github.hanhy06.emote.skin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MineSkinTextureStoreTest {
	@Test
	void saveAndLoadRoundTrip(@TempDir Path tempDir) {
		MineSkinTextureStore store = new MineSkinTextureStore(tempDir);
		Map<PlayerSkinTextureKey, String> savedTextureUrls = Map.of(
				new PlayerSkinTextureKey(PlayerSkinPart.HEAD, PlayerSkinSegment.FULL), "https://textures.minecraft.net/texture/head",
				new PlayerSkinTextureKey(PlayerSkinPart.LEFT_ARM, new PlayerSkinSegment(2, 8)), "https://textures.minecraft.net/texture/left_arm"
		);

		store.save("ABCDEF", true, savedTextureUrls);

		assertEquals(savedTextureUrls, store.load("abcdef", true));
	}

    @Test
    void contentCacheRoundTrip(@TempDir Path tempDir) {
        MineSkinTextureStore store = new MineSkinTextureStore(tempDir);
        String contentHash = MineSkinContentKey.create(new byte[]{1, 2, 3}, false);
        MineSkinTextureResult result = new MineSkinTextureResult("https://textures.minecraft.net/texture/shared");

        store.saveContent(contentHash, result);

        assertEquals(result, store.loadContent(contentHash));
    }

    @Test
    void contentKeyIncludesModelVariant() {
        byte[] image = new byte[]{4, 5, 6};

        assertNotEquals(MineSkinContentKey.create(image, false), MineSkinContentKey.create(image, true));
    }

    @Test
    void rejectsInvalidContentHash(@TempDir Path tempDir) {
        MineSkinTextureStore store = new MineSkinTextureStore(tempDir);

        assertNull(store.loadContent("../invalid"));
    }

    @Test
    void pendingJobRoundTrip(@TempDir Path tempDir) {
        MineSkinTextureStore store = new MineSkinTextureStore(tempDir);
        String contentHash = MineSkinContentKey.create(new byte[]{7, 8, 9}, true);

        store.savePendingJob(contentHash, "job-123");
        assertEquals("job-123", store.loadPendingJob(contentHash));

        store.clearPendingJob(contentHash);
        assertNull(store.loadPendingJob(contentHash));
    }
}
