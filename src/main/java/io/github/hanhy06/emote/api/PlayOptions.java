package io.github.hanhy06.emote.api;

import java.util.Objects;

public record PlayOptions(PlaybackPlacement placement) {
    public PlayOptions {
        Objects.requireNonNull(placement, "placement");
    }

    public static PlayOptions createDefault() {
        return new PlayOptions(PlaybackPlacement.actor());
    }
}
