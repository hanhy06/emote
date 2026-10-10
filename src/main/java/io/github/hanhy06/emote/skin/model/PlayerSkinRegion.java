package io.github.hanhy06.emote.skin.model;

import java.util.Objects;

public record PlayerSkinRegion(PlayerSkinPart skinPart, double from, double to) {
    public PlayerSkinRegion {
        Objects.requireNonNull(skinPart, "skinPart");
        if (!Double.isFinite(from) || !Double.isFinite(to) || from < 0 || from >= to || to > 1) {
            throw new IllegalArgumentException("region must satisfy 0 <= from < to <= 1");
        }
    }
}
