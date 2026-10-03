package io.github.hanhy06.emote.skin.model;

import java.util.Map;
import java.util.Objects;

public record PlayerSkinPreparation(
    Map<PlayerSkinRegion, String> textures,
    State state,
    int progressPercent
) {
    public PlayerSkinPreparation {
        if (textures != null) textures = Map.copyOf(textures);
        Objects.requireNonNull(state, "state");
        if (progressPercent < 0 || progressPercent > 100) {
            throw new IllegalArgumentException("progressPercent must be between 0 and 100");
        }
    }

    public boolean preparing() {
        return this.state == State.PREPARING;
    }

    public enum State {
        READY,
        PREPARING,
        FAILED,
        UNAVAILABLE
    }
}
