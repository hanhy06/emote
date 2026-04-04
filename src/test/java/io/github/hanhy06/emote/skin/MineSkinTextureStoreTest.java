package io.github.hanhy06.emote.skin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
