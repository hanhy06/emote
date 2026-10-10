package io.github.hanhy06.emote.api;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

public record EmoteInfo(
    Identifier id,
    Kind kind,
    boolean standalone,
    EmoteMetadata metadata,
    EmotePlayerBehavior playerBehavior,
    @Nullable Integer durationTicks,
    int cooldownTicks,
    EmoteAnimation.PlaybackMode playbackMode
) {
    public enum Kind { ANIMATION, SEQUENCE }

    public EmoteInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(playerBehavior, "playerBehavior");
        Objects.requireNonNull(playbackMode, "playbackMode");
    }

    public String getName() {
        return this.metadata.name();
    }

    public String getDescription() {
        return this.metadata.description();
    }
}
