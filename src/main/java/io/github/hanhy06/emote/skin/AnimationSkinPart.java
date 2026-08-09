package io.github.hanhy06.emote.skin;

import java.util.Objects;

public record AnimationSkinPart(
    String nodeId,
    PlayerSkinPart skinPart,
    PlayerSkinSegment skinSegment
) {
    public AnimationSkinPart {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(skinPart, "skinPart");
        Objects.requireNonNull(skinSegment, "skinSegment");
    }
}
