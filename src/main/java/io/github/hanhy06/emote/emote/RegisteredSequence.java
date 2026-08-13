package io.github.hanhy06.emote.emote;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.sequence.EmoteSequence;

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
        for (EmoteSequence.Step step : source.steps()) {
            List<RegisteredEmote> candidates = new ArrayList<>(step.emoteIds().size());
            for (var emoteId : step.emoteIds()) {
                RegisteredEmote animation = animations.get(emoteId.toString());
                if (animation == null) {
                    throw new IllegalArgumentException("Unknown or disabled animation: " + emoteId);
                }
                if (animation.loopMode() == EmoteAnimation.LoopMode.SERVER_SYNC) {
                    throw new IllegalArgumentException("Server-synchronized animation is not supported in a sequence: " + animation.id());
                }
                candidates.add(animation);
            }
            resolvedSteps.add(new Step(candidates, step.repeat()));
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
            int previousIndex = -1;
            for (int repeat = 0; repeat < step.repeat(); repeat++) {
                int selectedIndex = selectCandidateIndex(random, step.candidates().size(), previousIndex);
                selectedSteps.add(new SelectedStep(
                    step.candidates().get(selectedIndex),
                    repeat + 1 < step.repeat()
                ));
                previousIndex = selectedIndex;
            }
        }
        return selectedSteps;
    }

    private static List<SelectedStep> selectFirstCandidates(List<Step> steps) {
        List<SelectedStep> selectedSteps = new ArrayList<>();
        for (Step step : steps) {
            for (int repeat = 0; repeat < step.repeat(); repeat++) {
                selectedSteps.add(new SelectedStep(step.candidates().getFirst(), repeat + 1 < step.repeat()));
            }
        }
        return selectedSteps;
    }

    private static int selectCandidateIndex(RandomGenerator random, int size, int previousIndex) {
        if (size == 1) {
            return 0;
        }
        if (previousIndex < 0) {
            return random.nextInt(size);
        }
        int selectedIndex = random.nextInt(size - 1);
        return selectedIndex >= previousIndex ? selectedIndex + 1 : selectedIndex;
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
    public boolean standalone() {
        return true;
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
        return this.compiledAnimation.durationTicks();
    }

    @Override
    public EmoteAnimation.LoopMode loopMode() {
        return EmoteAnimation.LoopMode.ONCE;
    }

    @Override
    public int nodeCount() {
        return this.compiledAnimation.nodeCount();
    }

    public int displayNodeCount() {
        return this.compiledAnimation.displayNodeCount();
    }

    public record Step(List<RegisteredEmote> candidates, int repeat) {
        public Step {
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

    record SelectedStep(RegisteredEmote animation, boolean loopDelayAfter) {
        SelectedStep {
            Objects.requireNonNull(animation, "animation");
        }
    }
}
