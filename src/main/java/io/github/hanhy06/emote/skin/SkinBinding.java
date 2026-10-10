package io.github.hanhy06.emote.skin;

import io.github.hanhy06.emote.skin.model.PlayerSkinRegion;

import java.util.Objects;

public record SkinBinding(
    String nodeId,
    String attachmentId,
    PlayerSkinRegion region
) {
    public SkinBinding {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(attachmentId, "attachmentId");
        Objects.requireNonNull(region, "region");
    }
}
