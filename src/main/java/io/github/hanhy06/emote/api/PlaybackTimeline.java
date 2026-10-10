package io.github.hanhy06.emote.api;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

public record PlaybackTimeline(Identifier emoteId, @Nullable Integer durationTicks, EmoteAnimation.LoopMode loopMode,
                               int loopStartTick, List<Segment> segments) {
    public PlaybackTimeline {
        Objects.requireNonNull(emoteId, "emoteId");
        Objects.requireNonNull(loopMode, "loopMode");
        segments = List.copyOf(segments);
    }

    public enum Phase { START_DELAY, ANIMATION, TRANSITION, WAIT, LOOP_DELAY, HOLD }

    public record Segment(int segmentIndex, @Nullable Integer stepIndex, @Nullable Integer repeatIndex,
                          Phase phase, @Nullable Integer startTime, @Nullable Integer endTime, @Nullable Identifier animationId) {
        public Segment {
            Objects.requireNonNull(phase, "phase");
            if (segmentIndex < 0 || (startTime != null && startTime < 0) || (startTime != null && endTime != null && endTime < startTime)) {
                throw new IllegalArgumentException("Invalid timeline segment range");
            }
        }
    }
}
