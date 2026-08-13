package io.github.hanhy06.emote.api;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import net.minecraft.resources.Identifier;

import java.util.Objects;

public record EmoteInfo(
    Identifier id,
    EmoteMetadata metadata,
    EmotePlayerBehavior player,
    int durationTicks,
    EmoteAnimation.LoopMode loopMode
) {
    public EmoteInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(loopMode, "loopMode");
    }

    public String name() {
        return this.metadata.name();
    }

    public String description() {
        return this.metadata.description();
    }
}
