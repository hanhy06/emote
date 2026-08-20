package io.github.hanhy06.emote.content;

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

public record PreparedSequence(
    EmoteSequence source,
    Playback playback,
    PreparedAnimation layoutAnchor,
    PreparedAnimation compiledAnimation
) implements PlayableEmote {
    public PreparedSequence {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(playback, "playback");
        Objects.requireNonNull(layoutAnchor, "layoutAnchor");
        Objects.requireNonNull(compiledAnimation, "compiledAnimation");
    }

    public static PreparedSequence resolve(EmoteSequence source, Map<String, PreparedAnimation> animations) {
        Playback playback = resolvePlayback(source, animations);
        PreparedAnimation layoutAnchor = SequenceNodeLayout.validateAndCreateLayout(playback.validationSteps());
        List<SelectedStep> initialSteps = switch (playback) {
            case LinearPlayback linear -> selectFirstCandidates(linear.branch());
            case PartnerPlayback partner -> List.of(new SelectedEmoteStep(partner.offer(), false));
        };
        return new PreparedSequence(
            source,
            playback,
            layoutAnchor,
            SequenceCompiler.compile(source, initialSteps, layoutAnchor)
        );
    }

    private static Playback resolvePlayback(EmoteSequence source, Map<String, PreparedAnimation> animations) {
        if (source.steps().getFirst() instanceof EmoteSequence.AwaitPartnerStep await) {
            return new PartnerPlayback(
                resolveAnimation(await.offerEmoteId().toString(), animations),
                await.timeoutTicks(),
                resolveBranch(await.matched(), animations),
                resolveBranch(await.timeout(), animations)
            );
        }
        return new LinearPlayback(resolveBranch(source.steps(), animations));
    }

    private static Branch resolveBranch(List<EmoteSequence.Step> sourceSteps, Map<String, PreparedAnimation> animations) {
        List<Step> resolvedSteps = new ArrayList<>(sourceSteps.size());
        for (EmoteSequence.Step sourceStep : sourceSteps) {
            if (sourceStep instanceof EmoteSequence.WaitStep(int ticks)) {
                resolvedSteps.add(new WaitStep(ticks));
                continue;
            }
            EmoteSequence.EmoteStep step = (EmoteSequence.EmoteStep) sourceStep;
            List<Choice> candidates = new ArrayList<>(step.choices().size());
            for (EmoteSequence.Choice choice : step.choices()) {
                EmoteSequence.Control control = EmoteSequence.Control.fromId(choice.emoteId());
                if (control != null) {
                    candidates.add(new ControlChoice(control, choice.chance()));
                    continue;
                }
                PreparedAnimation animation = resolveAnimation(choice.emoteId().toString(), animations);
                candidates.add(new AnimationChoice(animation, choice.chance()));
            }
            resolvedSteps.add(new EmoteStep(candidates, step.repeat()));
        }
        return new Branch(resolvedSteps);
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
        if (!(this.playback instanceof LinearPlayback linear)) {
            throw new IllegalStateException("Partner sequence branches must be compiled separately: " + id());
        }
        return SequenceCompiler.compile(this.source, selectSteps(linear.branch(), random), this.layoutAnchor);
    }

    public PreparedAnimation compileMatch(RandomGenerator random) {
        return SequenceCompiler.compile(this.source, selectSteps(partnerPlayback().matched(), random), this.layoutAnchor);
    }

    public PreparedAnimation compileTimeout(RandomGenerator random) {
        return SequenceCompiler.compile(this.source, selectSteps(partnerPlayback().timeout(), random), this.layoutAnchor);
    }

    public boolean hasPartner() {
        return this.playback instanceof PartnerPlayback;
    }

    public PartnerPlayback partnerPlayback() {
        if (!(this.playback instanceof PartnerPlayback partnerPlayback)) {
            throw new IllegalStateException("Sequence is not partner: " + id());
        }
        return partnerPlayback;
    }

    List<SelectedStep> selectSteps(RandomGenerator random) {
        if (!(this.playback instanceof LinearPlayback linear)) {
            throw new IllegalStateException("Partner sequence branches must be selected separately: " + id());
        }
        return selectSteps(linear.branch(), random);
    }

    private static List<SelectedStep> selectSteps(Branch branch, RandomGenerator random) {
        Objects.requireNonNull(random, "random");
        List<SelectedStep> selectedSteps = new ArrayList<>();
        for (Step step : branch.steps()) {
            if (step instanceof WaitStep(int ticks)) {
                selectedSteps.add(new SelectedWaitStep(ticks));
                continue;
            }
            EmoteStep emoteStep = (EmoteStep) step;
            List<PreparedAnimation> selectedAnimations = new ArrayList<>();
            int animationCandidateCount = (int) emoteStep.candidates().stream().filter(AnimationChoice.class::isInstance).count();
            int previousAnimationIndex = -1;
            for (int repeat = 0; repeat < emoteStep.repeat(); repeat++) {
                int excludedIndex = animationCandidateCount > 1 ? previousAnimationIndex : -1;
                int selectedIndex = WeightedChoiceSelector.selectIndex(random, emoteStep.candidates(), Choice::chance, excludedIndex);
                Choice selected = emoteStep.candidates().get(selectedIndex);
                if (selected instanceof AnimationChoice animation) {
                    selectedAnimations.add(animation.animation());
                    previousAnimationIndex = selectedIndex;
                } else if (((ControlChoice) selected).control() == EmoteSequence.Control.BREAK) {
                    break;
                }
            }
            appendSelectedAnimations(selectedSteps, selectedAnimations);
        }
        return selectedSteps;
    }

    private static List<SelectedStep> selectFirstCandidates(Branch branch) {
        List<SelectedStep> selectedSteps = new ArrayList<>();
        for (Step step : branch.steps()) {
            if (step instanceof WaitStep(int ticks)) {
                selectedSteps.add(new SelectedWaitStep(ticks));
                continue;
            }
            EmoteStep emoteStep = (EmoteStep) step;
            List<PreparedAnimation> selectedAnimations = new ArrayList<>();
            for (int repeat = 0; repeat < emoteStep.repeat(); repeat++) {
                Choice selected = emoteStep.candidates().getFirst();
                if (selected instanceof AnimationChoice animation) {
                    selectedAnimations.add(animation.animation());
                } else if (((ControlChoice) selected).control() == EmoteSequence.Control.BREAK) {
                    break;
                }
            }
            appendSelectedAnimations(selectedSteps, selectedAnimations);
        }
        return selectedSteps;
    }

    private static void appendSelectedAnimations(List<SelectedStep> selectedSteps, List<PreparedAnimation> animations) {
        for (int index = 0; index < animations.size(); index++) {
            selectedSteps.add(new SelectedEmoteStep(animations.get(index), index + 1 < animations.size()));
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

    public sealed interface Playback permits LinearPlayback, PartnerPlayback {
        List<Step> validationSteps();
    }

    public record LinearPlayback(Branch branch) implements Playback {
        public LinearPlayback {
            Objects.requireNonNull(branch, "branch");
        }

        @Override
        public List<Step> validationSteps() {
            return this.branch.steps();
        }
    }

    public record PartnerPlayback(
        PreparedAnimation offer,
        int timeoutTicks,
        Branch matched,
        Branch timeout
    ) implements Playback {
        public PartnerPlayback {
            Objects.requireNonNull(offer, "offer");
            if (timeoutTicks < 1) {
                throw new IllegalArgumentException("await_partner timeout must be at least 1 tick");
            }
            Objects.requireNonNull(matched, "matched");
            Objects.requireNonNull(timeout, "timeout");
        }

        @Override
        public List<Step> validationSteps() {
            List<Step> steps = new ArrayList<>(1 + this.matched.steps().size() + this.timeout.steps().size());
            steps.add(new EmoteStep(List.of(new AnimationChoice(this.offer, 0)), 1));
            steps.addAll(this.matched.steps());
            steps.addAll(this.timeout.steps());
            return List.copyOf(steps);
        }
    }

    public record Branch(List<Step> steps) {
        public Branch {
            steps = List.copyOf(steps);
            if (steps.isEmpty()) {
                throw new IllegalArgumentException("sequence branch steps must not be empty");
            }
        }
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

    record SelectedEmoteStep(PreparedAnimation animation, boolean loopDelayAfter) implements SelectedStep {
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
