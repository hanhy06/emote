package io.github.hanhy06.emote.skin.animation;

import io.github.hanhy06.emote.skin.model.PlayerSkinRegion;

import java.util.Objects;

public record AnimationSkinBinding(
    String nodeId,
    PlayerSkinRegion region
) {
    public AnimationSkinBinding {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(region, "region");
    }
}
