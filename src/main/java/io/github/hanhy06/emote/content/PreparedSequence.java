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
    PreparedAnimation layoutAnchor,
    PreparedAnimation compiledAnimation
) implements PlayableEmote {
    public PreparedSequence {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sourcePath, "sourcePath");
        steps = List.copyOf(steps);
        if (steps.isEmpty()) throw new IllegalArgumentException("sequence steps must not be empty");
        Objects.requireNonNull(layoutAnchor, "layoutAnchor");
        Objects.requireNonNull(compiledAnimation, "compiledAnimation");
    }

    public static PreparedSequence resolve(EmoteSequence source, Map<String, PreparedAnimation> animations) {
        return resolve(new LoadedSequence(Path.of("api", source.id().getNamespace(), source.id().getPath() + ".json"), source), animations);
    }

    public static PreparedSequence resolve(LoadedSequence loaded, Map<String, PreparedAnimation> animations) {
        EmoteSequence source = loaded.sequence();
        List<Step> steps = resolveSteps(source.steps(), animations);
        PreparedAnimation layoutAnchor = SequenceNodeLayout.validateAndCreateLayout(steps);
        return new PreparedSequence(
            source,
            loaded.sourcePath(),
            steps,
            layoutAnchor,
            SequenceCompiler.compile(source, loaded.sourcePath(), selectFirstCandidates(steps), layoutAnchor)
        );
    }

    private static List<Step> resolveSteps(List<EmoteSequence.Step> sourceSteps, Map<String, PreparedAnimation> animations) {
        List<Step> resolvedSteps = new ArrayList<>(sourceSteps.size());
        for (EmoteSequence.Step sourceStep : sourceSteps) {
            if (sourceStep instanceof EmoteSequence.WaitStep(int ticks)) {
                resolvedSteps.add(new WaitStep(ticks));
                continue;
            }
            EmoteSequence.EmoteStep step = (EmoteSequence.EmoteStep) sourceStep;
            List<Choice> candidates = new ArrayList<>(step.choices().size());
            for (EmoteSequence.Choice choice : step.choices()) {
                EmoteSequence.Control control = EmoteSequence.Control.fromId(choice.targetId());
                if (control != null) {
                    candidates.add(new ControlChoice(control, choice.chance()));
                    continue;
                }
                PreparedAnimation animation = resolveAnimation(choice.targetId().toString(), animations);
                candidates.add(new AnimationChoice(animation, choice.chance()));
            }
            resolvedSteps.add(new EmoteStep(candidates, step.repeat(), step.transitionTicks()));
        }
        return List.copyOf(resolvedSteps);
    }

    private static PreparedAnimation resolveAnimation(String id, Map<String, PreparedAnimation> animations) {
        PreparedAnimation animation = animations.get(id);
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

    public PreparedAnimation compile(RandomGenerator random) {
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
            EmoteStep emoteStep = (EmoteStep) step;
            List<SelectedEmoteStep> selectedAnimations = new ArrayList<>();
            int animationCandidateCount = (int) emoteStep.candidates().stream().filter(AnimationChoice.class::isInstance).count();
            int previousAnimationIndex = -1;
            for (int repeat = 0; repeat < emoteStep.repeat(); repeat++) {
                int excludedIndex = animationCandidateCount > 1 ? previousAnimationIndex : -1;
                int selectedIndex = WeightedChoiceSelector.selectIndex(random, emoteStep.candidates(), Choice::chance, excludedIndex);
                Choice selected = emoteStep.candidates().get(selectedIndex);
                if (selected instanceof AnimationChoice animation) {
                    selectedAnimations.add(new SelectedEmoteStep(animation.animation(), false, emoteStep.transitionTicks(), stepIndex, repeat));
                    previousAnimationIndex = selectedIndex;
                } else if (((ControlChoice) selected).control() == EmoteSequence.Control.BREAK) {
                    break;
                }
            }
            appendSelectedAnimations(selectedSteps, selectedAnimations, emoteStep.transitionTicks());
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
            EmoteStep emoteStep = (EmoteStep) step;
            List<SelectedEmoteStep> selectedAnimations = new ArrayList<>();
            for (int repeat = 0; repeat < emoteStep.repeat(); repeat++) {
                Choice selected = emoteStep.candidates().getFirst();
                if (selected instanceof AnimationChoice animation) {
                    selectedAnimations.add(new SelectedEmoteStep(animation.animation(), false, emoteStep.transitionTicks(), stepIndex, repeat));
                } else if (((ControlChoice) selected).control() == EmoteSequence.Control.BREAK) {
                    break;
                }
            }
            appendSelectedAnimations(selectedSteps, selectedAnimations, emoteStep.transitionTicks());
        }
        return selectedSteps;
    }

    private static void appendSelectedAnimations(
        List<SelectedStep> selectedSteps,
        List<SelectedEmoteStep> animations,
        int transitionTicks
    ) {
        for (int index = 0; index < animations.size(); index++) {
            SelectedEmoteStep selected = animations.get(index);
            selectedSteps.add(new SelectedEmoteStep(
                selected.animation(),
                index + 1 < animations.size(),
                transitionTicks,
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

    @Override
    public Path sourcePath() {
        return this.sourcePath;
    }

    public @Nullable Integer fixedDurationTicks() {
        boolean fixed = this.steps.stream().allMatch(step -> step instanceof WaitStep
            || (step instanceof EmoteStep emote && emote.candidates().size() == 1 && emote.candidates().getFirst() instanceof AnimationChoice));
        return fixed ? this.compiledAnimation.durationTicks() : null;
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

    public record EmoteStep(List<Choice> candidates, int repeat, int transitionTicks) implements Step {
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
            if (transitionTicks < 0) {
                throw new IllegalArgumentException("sequence transition must not be negative");
            }
        }

        public EmoteStep(List<Choice> candidates, int repeat) {
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

    public record AnimationChoice(PreparedAnimation animation, int chance) implements Choice {
        public AnimationChoice {
            Objects.requireNonNull(animation, "animation");
        }
    }

    public record ControlChoice(EmoteSequence.Control control, int chance) implements Choice {
        public ControlChoice {
            Objects.requireNonNull(control, "control");
        }
    }

    sealed interface SelectedStep permits SelectedEmoteStep, SelectedWaitStep {
    }

    record SelectedEmoteStep(
        PreparedAnimation animation,
        boolean loopDelayAfter,
        int transitionTicks,
        int stepIndex,
        int repeatIndex
    ) implements SelectedStep {
        SelectedEmoteStep {
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
