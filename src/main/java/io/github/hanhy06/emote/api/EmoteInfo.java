package io.github.hanhy06.emote.api;

import io.github.hanhy06.emote.animation.EmoteAnimation;
import net.minecraft.resources.Identifier;

import java.util.Objects;

public record EmoteInfo(
    Identifier id,
    String name,
    String description,
    boolean hidePlayer,
    int durationTicks,
    EmoteAnimation.LoopMode loopMode
) {
    public EmoteInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(loopMode, "loopMode");
    }
}
