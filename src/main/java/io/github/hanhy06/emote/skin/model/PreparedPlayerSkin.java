package io.github.hanhy06.emote.skin.model;

import java.util.Map;
import java.util.Objects;

public record PreparedPlayerSkin(
    Map<PlayerSkinRegion, String> textureUrlMap
) {
    public PreparedPlayerSkin {
        Objects.requireNonNull(textureUrlMap, "textureUrlMap");
        textureUrlMap = Map.copyOf(textureUrlMap);
    }

    public String findTextureUrl(PlayerSkinRegion region) {
        return this.textureUrlMap.get(region);
    }
}
