package io.github.hanhy06.emote.skin.model;

import java.util.Objects;
import java.util.UUID;

public record PlayerSkinSource(
    UUID playerUuid,
    String playerName,
    String textureHash,
    String textureUrl,
    boolean slimModel
) {
    public PlayerSkinSource {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(textureHash, "textureHash");
        Objects.requireNonNull(textureUrl, "textureUrl");
    }
}
