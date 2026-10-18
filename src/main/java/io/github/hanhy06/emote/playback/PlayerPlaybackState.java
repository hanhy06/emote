package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

public record PlayerPlaybackState(
    Vec3 startPosition,
    boolean wasInvisible,
    EmotePlayerBehavior behavior
) {
    public PlayerPlaybackState {
        Objects.requireNonNull(startPosition, "startPosition");
        Objects.requireNonNull(behavior, "behavior");
    }
}
