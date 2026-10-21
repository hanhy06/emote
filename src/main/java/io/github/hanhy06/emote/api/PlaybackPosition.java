package io.github.hanhy06.emote.api;

import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

public record PlaybackPosition(int segmentIndex, @Nullable Integer stepIndex, @Nullable Integer repeatIndex,
                               PlaybackTimeline.Phase phase, int phaseTick,
                               @Nullable Identifier animationId, @Nullable Integer animationTick) {
    public PlaybackPosition {
        Objects.requireNonNull(phase, "phase");
    }
}
