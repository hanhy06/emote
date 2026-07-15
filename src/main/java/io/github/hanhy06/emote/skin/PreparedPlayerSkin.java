package io.github.hanhy06.emote.skin;

import java.util.List;
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

    public boolean containsAll(List<EmoteSkinPart> skinParts) {
        for (EmoteSkinPart skinPart : skinParts) {
            if (findTextureUrl(skinPart.skinPart(), skinPart.skinSegment()) == null) {
                return false;
            }
        }
        return true;
    }
}
