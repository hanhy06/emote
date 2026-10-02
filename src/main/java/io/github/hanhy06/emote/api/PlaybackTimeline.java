package io.github.hanhy06.emote.api;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/** The immutable timeline selected for one playback, rather than the sequence's candidates.
 * durationTicks is the animation/sequence timeline length; standalone loop delay and indefinite hold
 * are represented by trailing segments. Loop mode and loopStartTick describe subsequent cycles.
 */
public record PlaybackTimeline(Identifier emoteId, int durationTicks, EmoteAnimation.LoopMode loopMode,
                               int loopStartTick, List<Segment> segments) {
    public PlaybackTimeline {
        Objects.requireNonNull(emoteId, "emoteId");
        Objects.requireNonNull(loopMode, "loopMode");
        segments = List.copyOf(segments);
    }

    public enum Phase { ANIMATION, TRANSITION, WAIT, LOOP_DELAY, HOLD }

    /** Ranges are [startTick, endTick); a null endTick denotes an indefinite hold.
     * All indices are zero-based; step/repeat indices are absent for standalone animations.
     * TRANSITION identifies the incoming animation; WAIT and LOOP_DELAY have no animation ID.
     */
    public record Segment(int segmentIndex, @Nullable Integer stepIndex, @Nullable Integer repeatIndex,
                          Phase phase, long startTick, @Nullable Long endTick, @Nullable Identifier animationId) {
        public Segment {
            Objects.requireNonNull(phase, "phase");
            if (segmentIndex < 0 || startTick < 0 || (endTick != null && endTick <= startTick)) {
                throw new IllegalArgumentException("Invalid timeline segment range");
            }
        }
    }
}
