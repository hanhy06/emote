package io.github.hanhy06.emote.api;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import net.minecraft.resources.Identifier;

import java.util.Objects;

public record EmoteInfo(
    Identifier id,
    String name,
    String description,
    EmoteAnimation.PlayerBehavior player,
    int durationTicks,
    EmoteAnimation.LoopMode loopMode
) {
    public EmoteInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(loopMode, "loopMode");
    }
}
