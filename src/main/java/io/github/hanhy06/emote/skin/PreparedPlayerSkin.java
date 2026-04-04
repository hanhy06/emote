package io.github.hanhy06.emote.skin;

import java.util.Map;
import java.util.Objects;

public record PreparedPlayerSkin(
		String textureHash,
		boolean slimModel,
		Map<PlayerSkinTextureKey, String> textureUrlMap
) {
	public PreparedPlayerSkin {
		Objects.requireNonNull(textureHash, "textureHash");
		textureUrlMap = Map.copyOf(textureUrlMap);
	}

	public PreparedPlayerSkin(String textureHash, boolean slimModel) {
		this(textureHash, slimModel, Map.of());
	}

	public String findTextureUrl(PlayerSkinPart skinPart, PlayerSkinSegment skinSegment) {
		return this.textureUrlMap.get(new PlayerSkinTextureKey(skinPart, skinSegment));
	}
}
