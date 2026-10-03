package io.github.hanhy06.emote.content;

import io.github.hanhy06.emote.api.sequence.EmoteSequence;

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
import org.jspecify.annotations.Nullable;

public record PreparedSequence(
    EmoteSequence source,
    Path sourcePath,
    List<Step> steps,
    PreparedEmote layoutAnchor,
    PreparedEmote compiledEmote
) implements PlayableEmote {
    public PreparedSequence {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sourcePath, "sourcePath");
        steps = List.copyOf(steps);
        if (steps.isEmpty()) throw new IllegalArgumentException("sequence steps must not be empty");
        Objects.requireNonNull(layoutAnchor, "layoutAnchor");
        Objects.requireNonNull(compiledEmote, "compiledEmote");
    }

    public static PreparedSequence resolve(EmoteSequence source, Map<String, PreparedEmote> animations) {
        return resolve(new LoadedSequence(Path.of("api", source.id().getNamespace(), source.id().getPath() + ".json"), source), animations);
    }

    public static PreparedSequence resolve(LoadedSequence loaded, Map<String, PreparedEmote> animations) {
        EmoteSequence source = loaded.sequence();
        List<Step> steps = resolveSteps(source.steps(), animations);
        PreparedEmote layoutAnchor = SequenceNodeLayout.validateAndCreateLayout(steps);
        return new PreparedSequence(
            source,
            loaded.sourcePath(),
            steps,
            layoutAnchor,
            SequenceCompiler.compile(source, loaded.sourcePath(), selectFirstCandidates(steps), layoutAnchor)
        );
    }

    private static List<Step> resolveSteps(List<EmoteSequence.Step> sourceSteps, Map<String, PreparedEmote> animations) {
        List<Step> resolvedSteps = new ArrayList<>(sourceSteps.size());
        for (EmoteSequence.Step sourceStep : sourceSteps) {
            if (sourceStep instanceof EmoteSequence.WaitStep(int ticks)) {
                resolvedSteps.add(new WaitStep(ticks));
                continue;
            }
            EmoteSequence.AnimationStep step = (EmoteSequence.AnimationStep) sourceStep;
            List<Choice> candidates = new ArrayList<>(step.choices().size());
            for (EmoteSequence.Choice choice : step.choices()) {
                EmoteSequence.Control control = EmoteSequence.Control.fromId(choice.targetId());
                if (control != null) {
                    candidates.add(new ControlChoice(control, choice.chance()));
                    continue;
                }
                PreparedEmote animation = resolveAnimation(choice.targetId().toString(), animations);
                candidates.add(new AnimationChoice(animation, choice.chance()));
            }
            resolvedSteps.add(new AnimationStep(candidates, step.repeat(), step.transitionTicks()));
        }
        return List.copyOf(resolvedSteps);
    }

    private static PreparedEmote resolveAnimation(String id, Map<String, PreparedEmote> animations) {
        PreparedEmote animation = animations.get(id);
        if (animation == null) {
            throw new IllegalArgumentException("Unknown or disabled animation: " + id);
        }
        if (animation.loopMode() == EmoteAnimation.LoopMode.SERVER_SYNC) {
            throw new IllegalArgumentException("Server-synchronized animation is not supported in a sequence: " + animation.id());
        }
        if (animation.loopMode() == EmoteAnimation.LoopMode.HOLD) {
            throw new IllegalArgumentException("Hold animation is not supported in a sequence: " + animation.id());
        }
        return animation;
    }

    public PreparedEmote compile(RandomGenerator random) {
        return SequenceCompiler.compile(this.source, this.sourcePath, selectSteps(this.steps, random), this.layoutAnchor);
    }

    List<SelectedStep> selectSteps(RandomGenerator random) {
        return selectSteps(this.steps, random);
    }

    private static List<SelectedStep> selectSteps(List<Step> steps, RandomGenerator random) {
        Objects.requireNonNull(random, "random");
        List<SelectedStep> selectedSteps = new ArrayList<>();
        for (int stepIndex = 0; stepIndex < steps.size(); stepIndex++) {
            Step step = steps.get(stepIndex);
            if (step instanceof WaitStep(int ticks)) {
                selectedSteps.add(new SelectedWaitStep(ticks, stepIndex));
                continue;
            }
            AnimationStep emoteStep = (AnimationStep) step;
            List<SelectedAnimationStep> selectedAnimations = new ArrayList<>();
            int animationCandidateCount = (int) emoteStep.candidates().stream().filter(AnimationChoice.class::isInstance).count();
            int previousAnimationIndex = -1;
            for (int repeat = 0; repeat < emoteStep.repeat(); repeat++) {
                int excludedIndex = animationCandidateCount > 1 ? previousAnimationIndex : -1;
                int selectedIndex = WeightedChoiceSelector.selectIndex(random, emoteStep.candidates(), Choice::chance, excludedIndex);
                Choice selected = emoteStep.candidates().get(selectedIndex);
                if (selected instanceof AnimationChoice animation) {
                    selectedAnimations.add(new SelectedAnimationStep(animation.animation(), false, emoteStep.transitionTicks(), stepIndex, repeat));
                    previousAnimationIndex = selectedIndex;
                } else if (((ControlChoice) selected).control() == EmoteSequence.Control.BREAK) {
                    break;
                }
            }
            appendSelectedAnimations(selectedSteps, selectedAnimations);
        }
        return selectedSteps;
    }

    private static List<SelectedStep> selectFirstCandidates(List<Step> steps) {
        List<SelectedStep> selectedSteps = new ArrayList<>();
        for (int stepIndex = 0; stepIndex < steps.size(); stepIndex++) {
            Step step = steps.get(stepIndex);
            if (step instanceof WaitStep(int ticks)) {
                selectedSteps.add(new SelectedWaitStep(ticks, stepIndex));
                continue;
            }
            AnimationStep emoteStep = (AnimationStep) step;
            List<SelectedAnimationStep> selectedAnimations = new ArrayList<>();
            for (int repeat = 0; repeat < emoteStep.repeat(); repeat++) {
                Choice selected = emoteStep.candidates().getFirst();
                if (selected instanceof AnimationChoice animation) {
                    selectedAnimations.add(new SelectedAnimationStep(animation.animation(), false, emoteStep.transitionTicks(), stepIndex, repeat));
                } else if (((ControlChoice) selected).control() == EmoteSequence.Control.BREAK) {
                    break;
                }
            }
            appendSelectedAnimations(selectedSteps, selectedAnimations);
        }
        return selectedSteps;
    }

    private static void appendSelectedAnimations(
        List<SelectedStep> selectedSteps,
        List<SelectedAnimationStep> animations
    ) {
        for (int index = 0; index < animations.size(); index++) {
            SelectedAnimationStep selected = animations.get(index);
            selectedSteps.add(new SelectedAnimationStep(
                selected.animation(),
                index + 1 < animations.size(),
                selected.transitionTicks(),
                selected.stepIndex(),
                selected.repeatIndex()
            ));
        }
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

    public @Nullable Integer fixedDurationTicks() {
        boolean fixed = this.steps.stream().allMatch(step -> step instanceof WaitStep
            || (step instanceof AnimationStep emote && emote.candidates().size() == 1 && emote.candidates().getFirst() instanceof AnimationChoice));
        return fixed ? this.compiledEmote.durationTicks() : null;
    }

    @Override
    public int durationTicks() {
        return this.compiledEmote.durationTicks();
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
        return this.compiledEmote.nodeCount();
    }

    public sealed interface Step permits AnimationStep, WaitStep {
    }

    public record AnimationStep(List<Choice> candidates, int repeat, int transitionTicks) implements Step {
        public AnimationStep {
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
            if (transitionTicks < 0) {
                throw new IllegalArgumentException("sequence transition must not be negative");
            }
        }

        public AnimationStep(List<Choice> candidates, int repeat) {
            this(candidates, repeat, 0);
        }
    }

    public record WaitStep(int ticks) implements Step {
        public WaitStep {
            if (ticks < 1) {
                throw new IllegalArgumentException("sequence wait must be at least 1 tick");
            }
        }
    }

    public sealed interface Choice permits AnimationChoice, ControlChoice {
        int chance();
    }

    public record AnimationChoice(PreparedEmote animation, int chance) implements Choice {
        public AnimationChoice {
            Objects.requireNonNull(animation, "animation");
        }
    }

    public record ControlChoice(EmoteSequence.Control control, int chance) implements Choice {
        public ControlChoice {
            Objects.requireNonNull(control, "control");
        }
    }

    sealed interface SelectedStep permits SelectedAnimationStep, SelectedWaitStep {
    }

    record SelectedAnimationStep(
        PreparedEmote animation,
        boolean loopDelayAfter,
        int transitionTicks,
        int stepIndex,
        int repeatIndex
    ) implements SelectedStep {
        SelectedAnimationStep {
            Objects.requireNonNull(animation, "animation");
            if (transitionTicks < 0) {
                throw new IllegalArgumentException("sequence transition must not be negative");
            }
        }
    }

    record SelectedWaitStep(int ticks, int stepIndex) implements SelectedStep {
        SelectedWaitStep {
            if (ticks < 1) {
                throw new IllegalArgumentException("sequence wait must be at least 1 tick");
            }
        }
    }
}
