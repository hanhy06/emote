package io.github.hanhy06.emote.api;

import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/** A snapshot of the current segment. Animation ticks are present only in ANIMATION phase.
 * Ending-tick animation callbacks still observe their own segment; after advancing, the next
 * segment is reported. Closed playback preserves its final position, including delay/hold phase.
 */
public record PlaybackPosition(int segmentIndex, @Nullable Integer stepIndex, @Nullable Integer repeatIndex,
                               PlaybackTimeline.Phase phase, long phaseTick,
                               @Nullable Identifier animationId, @Nullable Integer animationTick) {
    public PlaybackPosition {
        Objects.requireNonNull(phase, "phase");
    }
}
