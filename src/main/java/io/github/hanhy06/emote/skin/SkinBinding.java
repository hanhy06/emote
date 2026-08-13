package io.github.hanhy06.emote.skin;

import io.github.hanhy06.emote.api.ParticipantRole;
import io.github.hanhy06.emote.skin.model.PlayerSkinRegion;

import java.util.Objects;

public record SkinBinding(
    String nodeId,
    ParticipantRole participant,
    PlayerSkinRegion region
) {
    public SkinBinding {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(participant, "participant");
        Objects.requireNonNull(region, "region");
    }
}
