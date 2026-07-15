package io.github.hanhy06.emote.skin;

import java.util.Objects;

public record JsonEmoteSkinPart(
    String nodeId,
    PlayerSkinPart skinPart,
    PlayerSkinSegment skinSegment
) {
    public JsonEmoteSkinPart {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(skinPart, "skinPart");
        Objects.requireNonNull(skinSegment, "skinSegment");
    }
}
