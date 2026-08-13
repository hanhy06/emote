package io.github.hanhy06.emote.skin.animation;

import io.github.hanhy06.emote.skin.model.PlayerSkinPart;
import io.github.hanhy06.emote.skin.model.PlayerSkinSegment;

import java.util.Objects;

public record AnimationSkinBinding(
    String nodeId,
    PlayerSkinPart skinPart,
    PlayerSkinSegment skinSegment
) {
    public AnimationSkinBinding {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(skinPart, "skinPart");
        Objects.requireNonNull(skinSegment, "skinSegment");
    }
}
