package io.github.hanhy06.emote.api;

import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

public record PlaybackCursor(int segmentIndex, @Nullable Integer stepIndex, @Nullable Integer repeatIndex,
                               PlaybackTimeline.Phase phase, int segmentTick,
                               @Nullable Identifier animationId, @Nullable Integer animationTick) {
    public PlaybackCursor {
        Objects.requireNonNull(phase, "phase");
    }
}
