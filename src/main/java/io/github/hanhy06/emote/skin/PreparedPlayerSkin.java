package io.github.hanhy06.emote.skin;

import java.util.Map;
import java.util.Objects;

public record PreparedPlayerSkin(
		Map<PlayerSkinTextureKey, String> textureUrlMap
) {
	public PreparedPlayerSkin {
		Objects.requireNonNull(textureUrlMap, "textureUrlMap");
		textureUrlMap = Map.copyOf(textureUrlMap);
	}

	public String findTextureUrl(PlayerSkinPart skinPart, PlayerSkinSegment skinSegment) {
		return this.textureUrlMap.get(new PlayerSkinTextureKey(skinPart, skinSegment));
	}
}
