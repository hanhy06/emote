package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.skin.SkinBinding;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Objects;

public record PlayerPlaybackState(
    Vec3 startPosition,
    List<SkinBinding> skinBindings,
    boolean wasInvisible,
    EmotePlayerBehavior behavior
) {
    public PlayerPlaybackState {
        Objects.requireNonNull(startPosition, "startPosition");
        skinBindings = List.copyOf(skinBindings);
        Objects.requireNonNull(behavior, "behavior");
    }
}
