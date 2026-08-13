package io.github.hanhy06.emote.emote;

import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.util.WeightedChoiceSelector;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.random.RandomGenerator;

public record RegisteredSequence(
    EmoteSequence source,
    List<Step> steps,
    RegisteredEmote compiledAnimation
) implements EmoteDefinition {
    public RegisteredSequence {
        Objects.requireNonNull(source, "source");
        steps = List.copyOf(steps);
        Objects.requireNonNull(compiledAnimation, "compiledAnimation");
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("sequence steps must not be empty");
        }
    }

    public static RegisteredSequence resolve(EmoteSequence source, Map<String, RegisteredEmote> animations) {
        List<Step> resolvedSteps = new ArrayList<>(source.steps().size());
        for (EmoteSequence.Step sourceStep : source.steps()) {
            if (sourceStep instanceof EmoteSequence.WaitStep(int ticks)) {
                resolvedSteps.add(new WaitStep(ticks));
                continue;
            }
            EmoteSequence.EmoteStep step = (EmoteSequence.EmoteStep) sourceStep;
            List<Choice> candidates = new ArrayList<>(step.choices().size());
            for (EmoteSequence.Choice choice : step.choices()) {
                RegisteredEmote animation = animations.get(choice.emoteId().toString());
                if (animation == null) {
                    throw new IllegalArgumentException("Unknown or disabled animation: " + choice.emoteId());
                }
                if (animation.loopMode() == EmoteAnimation.LoopMode.SERVER_SYNC) {
                    throw new IllegalArgumentException("Server-synchronized animation is not supported in a sequence: " + animation.id());
                }
                candidates.add(new Choice(animation, choice.chance()));
            }
            resolvedSteps.add(new EmoteStep(candidates, step.repeat()));
        }
        SequenceAnimationCompiler.validateCompatibleAnimations(resolvedSteps);
        return new RegisteredSequence(
            source,
            resolvedSteps,
            SequenceAnimationCompiler.compile(source, selectFirstCandidates(resolvedSteps))
        );
    }

    public RegisteredEmote compileRandom(RandomGenerator random) {
        return SequenceAnimationCompiler.compile(this.source, selectSteps(random));
    }

    List<SelectedStep> selectSteps(RandomGenerator random) {
        Objects.requireNonNull(random, "random");
        List<SelectedStep> selectedSteps = new ArrayList<>();
        for (Step step : this.steps) {
            if (step instanceof WaitStep(int ticks)) {
                selectedSteps.add(new SelectedWaitStep(ticks));
                continue;
            }
            EmoteStep emoteStep = (EmoteStep) step;
            int previousIndex = -1;
            for (int repeat = 0; repeat < emoteStep.repeat(); repeat++) {
                int selectedIndex = WeightedChoiceSelector.selectIndex(random, emoteStep.candidates(), Choice::chance, previousIndex);
                selectedSteps.add(new SelectedEmoteStep(
                    emoteStep.candidates().get(selectedIndex).animation(),
                    repeat + 1 < emoteStep.repeat()
                ));
                previousIndex = selectedIndex;
            }
        }
        return selectedSteps;
    }

    private static List<SelectedStep> selectFirstCandidates(List<Step> steps) {
        List<SelectedStep> selectedSteps = new ArrayList<>();
        for (Step step : steps) {
            if (step instanceof WaitStep(int ticks)) {
                selectedSteps.add(new SelectedWaitStep(ticks));
                continue;
            }
            EmoteStep emoteStep = (EmoteStep) step;
            for (int repeat = 0; repeat < emoteStep.repeat(); repeat++) {
                selectedSteps.add(new SelectedEmoteStep(
                    emoteStep.candidates().getFirst().animation(),
                    repeat + 1 < emoteStep.repeat()
                ));
            }
        }
        return selectedSteps;
    }

    @Override
    public String id() {
        return this.source.id().toString();
    }

    @Override
    public EmoteMetadata metadata() {
        return this.source.metadata();
    }

    @Override
    public boolean standalone() {
        return true;
    }

    @Override
    public EmotePlayerBehavior playerBehavior() {
        return this.source.settings().player();
    }

    @Override
    public Path sourcePath() {
        return this.source.sourcePath();
    }

    @Override
    public int durationTicks() {
        return this.compiledAnimation.durationTicks();
    }

    @Override
    public int cooldownTicks() {
        return this.source.settings().cooldownTicks();
    }

    @Override
    public EmoteAnimation.LoopMode loopMode() {
        return EmoteAnimation.LoopMode.ONCE;
    }

    @Override
    public int nodeCount() {
        return this.compiledAnimation.nodeCount();
    }

    public sealed interface Step permits EmoteStep, WaitStep {
    }

    public record EmoteStep(List<Choice> candidates, int repeat) implements Step {
        public EmoteStep {
            candidates = List.copyOf(candidates);
            if (candidates.isEmpty()) {
                throw new IllegalArgumentException("sequence emote candidates must not be empty");
            }
            if (candidates.stream().anyMatch(Objects::isNull)) {
                throw new NullPointerException("candidates");
            }
            if (repeat < 1) {
                throw new IllegalArgumentException("sequence repeat must be at least 1");
            }
        }
    }

    public record WaitStep(int ticks) implements Step {
        public WaitStep {
            if (ticks < 1) {
                throw new IllegalArgumentException("sequence wait must be at least 1 tick");
            }
        }
    }

    public record Choice(RegisteredEmote animation, int chance) {
        public Choice {
            Objects.requireNonNull(animation, "animation");
        }
    }

    sealed interface SelectedStep permits SelectedEmoteStep, SelectedWaitStep {
    }

    record SelectedEmoteStep(RegisteredEmote animation, boolean loopDelayAfter) implements SelectedStep {
        SelectedEmoteStep {
            Objects.requireNonNull(animation, "animation");
        }
    }

    record SelectedWaitStep(int ticks) implements SelectedStep {
        SelectedWaitStep {
            if (ticks < 1) {
                throw new IllegalArgumentException("sequence wait must be at least 1 tick");
            }
        }
    }
}
