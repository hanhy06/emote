package io.github.hanhy06.emote.emote;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.sequence.EmoteSequence;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record RegisteredSequence(EmoteSequence source, List<Step> steps) implements EmoteDefinition {
    public RegisteredSequence {
        Objects.requireNonNull(source, "source");
        steps = List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("sequence steps must not be empty");
        }
    }

    public static RegisteredSequence resolve(EmoteSequence source, Map<String, RegisteredEmote> animations) {
        List<Step> resolvedSteps = new ArrayList<>(source.steps().size());
        for (EmoteSequence.Step step : source.steps()) {
            RegisteredEmote animation = animations.get(step.emoteId().toString());
            if (animation == null) {
                throw new IllegalArgumentException("Unknown or disabled animation: " + step.emoteId());
            }
            if (animation.loopMode() == EmoteAnimation.LoopMode.SERVER_SYNC) {
                throw new IllegalArgumentException("Server-synchronized animation is not supported in a sequence: " + animation.id());
            }
            resolvedSteps.add(new Step(animation, step.repeat()));
        }
        return new RegisteredSequence(source, resolvedSteps);
    }

    @Override
    public String id() {
        return this.source.id().toString();
    }

    @Override
    public String name() {
        return this.source.metadata().name();
    }

    @Override
    public String description() {
        return this.source.metadata().description();
    }

    @Override
    public EmoteAnimation.PlayerBehavior playerBehavior() {
        return this.source.player();
    }

    @Override
    public Path sourcePath() {
        return this.source.sourcePath();
    }

    @Override
    public int durationTicks() {
        long total = 0L;
        for (Step step : this.steps) {
            long duration = step.animation().durationTicks();
            long loopDelay = step.animation().animation().timeline().loopDelayTicks();
            total += duration * step.repeat() + loopDelay * Math.max(0, step.repeat() - 1L);
        }
        return (int) Math.min(total, Integer.MAX_VALUE);
    }

    @Override
    public EmoteAnimation.LoopMode loopMode() {
        return EmoteAnimation.LoopMode.ONCE;
    }

    @Override
    public int nodeCount() {
        return this.steps.stream().mapToInt(step -> step.animation().nodeCount()).max().orElse(0);
    }

    public int displayNodeCount() {
        return this.steps.stream().mapToInt(step -> step.animation().displayNodeCount()).max().orElse(0);
    }

    public record Step(RegisteredEmote animation, int repeat) {
        public Step {
            Objects.requireNonNull(animation, "animation");
            if (repeat < 1) {
                throw new IllegalArgumentException("sequence repeat must be at least 1");
            }
        }
    }
}
