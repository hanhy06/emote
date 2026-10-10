package io.github.hanhy06.emote.playback;

import com.mojang.math.Transformation;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.api.PlaybackPosition;
import io.github.hanhy06.emote.api.PlaybackTimeline;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.content.AnimationEventPhase;
import io.github.hanhy06.emote.content.PreparedEmote;
import io.github.hanhy06.emote.content.PreparedSequence;
import org.jspecify.annotations.Nullable;
import io.github.hanhy06.emote.playback.molang.MolangQuerySource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import io.github.hanhy06.emote.content.PreparedAnimation;
import java.util.Comparator;
import java.util.random.RandomGenerator;
import io.github.hanhy06.emote.api.sequence.EmoteSequence;
import io.github.hanhy06.emote.util.WeightedChoiceSelector;
import io.github.hanhy06.emote.content.PreparedSequence.*;

public final class PlaybackPlayer {
    private final EmoteAnimation model;
    private final PreparedEmote emote;
    private final List<SelectedStep> selectedSteps;
    private final List<PlaybackTimeline.Segment> sequencePhases;
    private TimelineTarget target;
    private final MolangQuerySource querySource;
    private final AnimationEvaluator evaluator;
    private final Map<String, Matrix4f> appliedTransforms = new HashMap<>();
    private final Map<String, Boolean> appliedVisibility = new HashMap<>();
    private final Map<String, Map<String, Boolean>> appliedAttachmentVisibility = new HashMap<>();
    private final Map<String, Map<String, CompoundTag>> appliedNbt = new HashMap<>();
    private final Map<String, Map<String, Set<String>>> appliedNbtRemoved = new HashMap<>();

    private static final int MAX_LOOP_BOUNDARIES_PER_FRAME = 128;
    private int currentTick;
    private long lifetimeTicks;
    private long cycleTick;
    private int startDelay;
    private int remainingDelay;
    private int sampledLoopDelay;
    private int loopOverflow;
    private int frameDelta;
    private int frameBoundaries;
    private boolean pendingUpdate;
    private int loopCount;
    private PlaybackPlayer child;
    private SelectedAnimationStep childSelection;
    private int sequenceSegment;
    private int phaseTick;
    private boolean childCallbacksStarted;
    private boolean sequenceCycle;
    private final Map<Integer, Integer> segmentLengths = new HashMap<>();
    private final Map<Integer, Integer> segmentStarts = new HashMap<>();
    private PlaybackPhase phase = PlaybackPhase.NOT_STARTED;
    private boolean initialVisibilityDeferred;
    private EventExecutor eventExecutor;
    private boolean eventsStarted;
    private boolean animationEventsActive;
    private boolean eventsStopped;
    private LifecycleListener lifecycleListener = new LifecycleListener() {};
    private long holdTicks;
    private PlaybackPosition stoppedPosition;
    private final Map<String, Matrix4f> transitionStartTransforms = new HashMap<>();

    public PlaybackPlayer(PreparedEmote emote) {
        this(emote, MolangQuerySource.EMPTY, RandomGenerator.getDefault());
    }

    public PlaybackPlayer(PreparedEmote emote, MolangQuerySource querySource) {
        this(emote, querySource, RandomGenerator.getDefault());
    }

    public PlaybackPlayer(PreparedEmote emote, MolangQuerySource querySource, RandomGenerator random) {
        this.emote = Objects.requireNonNull(emote, "emote");
        this.querySource = Objects.requireNonNull(querySource, "querySource");
        this.model = emote instanceof PreparedAnimation animation ? animation.model() : null;
        this.evaluator = emote instanceof PreparedAnimation animation ? new AnimationEvaluator(animation, querySource) : null;
        this.selectedSteps = emote instanceof PreparedSequence sequence ? List.copyOf(selectSteps(sequence.steps(), random)) : List.of();
        this.sequencePhases = emote instanceof PreparedSequence ? sequencePhases(this.selectedSteps) : List.of();
    }

    public List<PreparedAnimation> selectedAnimations() {
        return this.selectedSteps.stream().filter(SelectedAnimationStep.class::isInstance).map(SelectedAnimationStep.class::cast)
            .map(SelectedAnimationStep::animation).distinct().toList();
    }

    public void start(TimelineTarget target) {
        if (this.phase != PlaybackPhase.NOT_STARTED) throw new IllegalStateException("Timeline already started");
        this.target = Objects.requireNonNull(target, "target");
        this.phase = PlaybackPhase.RUNNING;
        this.target.resetAll();
        if (this.emote instanceof PreparedSequence) {
            enterSequencePhase(true);
        } else {
            initializeAnimation();
            this.phase = this.startDelay > 0 ? PlaybackPhase.START_DELAY : PlaybackPhase.RUNNING;
            this.remainingDelay = this.startDelay;
            applyAnimationPose(0, false);
        }
    }

    public void startSynchronized(TimelineTarget target, long serverTick) {
        if (this.phase != PlaybackPhase.NOT_STARTED) throw new IllegalStateException("Timeline already started");
        if (this.emote.playbackMode() != EmoteAnimation.PlaybackMode.SERVER_SYNC) throw new IllegalStateException("Timeline is not server synchronized");
        this.target = Objects.requireNonNull(target, "target");
        startAtCyclePhaseUnchecked(serverTick);
    }

    public void startAtCyclePhase(TimelineTarget target, long cycleTick) {
        if (this.phase != PlaybackPhase.NOT_STARTED) throw new IllegalStateException("Timeline already started");
        this.target = Objects.requireNonNull(target, "target");
        startAtCyclePhaseUnchecked(cycleTick);
    }

    private void initializeAnimation() {
        clearState();
        this.evaluator.initialize(0, 0);
        this.startDelay = this.evaluator.delay(this.model.settings().playback().startDelay(), 0, 0, 0, 0);
    }

    private void startAtCyclePhaseUnchecked(long cycleTick) {
        if (this.emote instanceof PreparedSequence) throw new IllegalStateException("Cycle phase placement requires an animation");
        var playback = this.model.settings().playback();
        if ( this.model.clip().clock() != null || this.model.molang().update() != null
            || !(playback.startDelay() instanceof EmoteAnimation.ConstantValue) || !(playback.loopDelay() instanceof EmoteAnimation.ConstantValue delay)
            || this.model.clip().tracks().values().stream().flatMap(track -> track.driver().keys().stream()).anyMatch(key -> key.post() instanceof EmoteAnimation.MolangNbtValue)) {
            throw new IllegalStateException("Cycle phase placement requires a reconstructible clock and fixed delays");
        }
        this.target.resetAll();
        initializeAnimation();
        this.cycleTick = Math.max(0, cycleTick);
        this.sampledLoopDelay = (int) delay.value();
        placeAtCycleTick(this.cycleTick);
        applyAnimationPose(0, false);
    }

    private void placeAtCycleTick(long elapsed) {
        var playback = this.model.settings().playback();
        int durationTicks = this.model.clip().durationTicks();
        int loopStartTick = playback.loopStartTick();
        this.loopCount = 0;
        this.currentTick = 0;
        this.remainingDelay = 0;
        this.phase = PlaybackPhase.RUNNING;
        if (elapsed < this.startDelay) {
            this.phase = PlaybackPhase.START_DELAY;
            this.remainingDelay = Math.toIntExact(this.startDelay - elapsed);
        } else {
            elapsed -= this.startDelay;
            if (playback.mode() == EmoteAnimation.PlaybackMode.ONCE || playback.mode() == EmoteAnimation.PlaybackMode.HOLD) {
                this.currentTick = (int) Math.min(elapsed, durationTicks);
                if (elapsed >= durationTicks) this.phase = playback.mode() == EmoteAnimation.PlaybackMode.HOLD ? PlaybackPhase.HOLDING : PlaybackPhase.FINISHED;
            } else {
                long firstCycle = (long) durationTicks + this.sampledLoopDelay;
                long cycleLength = (long) durationTicks - loopStartTick + this.sampledLoopDelay;
                long phaseTick = elapsed;
                if (elapsed >= firstCycle) {
                    long cycles = (elapsed - firstCycle) / cycleLength;
                    this.loopCount = Math.toIntExact(1 + cycles);
                    phaseTick = elapsed - firstCycle - cycles * cycleLength;
                    this.currentTick = loopStartTick;
                }
                int activeLength = durationTicks - this.currentTick;
                this.currentTick += (int) Math.min(phaseTick, activeLength);
                if (phaseTick >= activeLength && this.sampledLoopDelay > 0) {
                    this.phase = PlaybackPhase.LOOP_DELAY;
                    this.remainingDelay = Math.toIntExact(cycleLength - phaseTick);
                    if (this.loopCount == 0) this.remainingDelay = Math.toIntExact(firstCycle - phaseTick);
                }
            }
        }
    }

    public void deferInitialVisibility() {
        if (this.phase == PlaybackPhase.NOT_STARTED) {
            throw new IllegalStateException("Timeline has not started");
        }
        this.emote.nodes().keySet().forEach(nodeId -> this.target.setVisible(nodeId, false));
        this.initialVisibilityDeferred = true;
    }

    public void restoreDeferredVisibility() {
        if (!this.initialVisibilityDeferred) {
            return;
        }
        this.initialVisibilityDeferred = false;
        this.emote.nodes().forEach((nodeId, node) -> this.target.setVisible(
            nodeId,
            this.appliedVisibility.getOrDefault(nodeId, node.visible())
        ));
    }

    public void bindEvents(EventExecutor eventExecutor) {
        if (this.eventExecutor != null) {
            throw new IllegalStateException("Animation events are already bound");
        }
        this.eventExecutor = Objects.requireNonNull(eventExecutor, "eventExecutor");
        if (this.child != null && this.child.eventExecutor == null) this.child.bindEvents(eventExecutor);
    }

    public void bindLifecycleListener(LifecycleListener listener) {
        this.lifecycleListener = Objects.requireNonNull(listener, "listener");
    }

    public void startEvents() {
        if (this.eventsStarted) {
            throw new IllegalStateException("Events already started");
        }
        this.eventsStarted = true;
        if ((this.emote instanceof PreparedSequence)) {
            startChildEvents(true);
            return;
        }
        if (this.phase != PlaybackPhase.START_DELAY) activateAnimationEvents(this.currentTick == 0);
    }

    private void activateAnimationEvents(boolean startAtZero) {
        if (!this.eventsStarted || this.animationEventsActive || this.eventsStopped) return;
        this.animationEventsActive = true;
        execute(this.model.clip().events().start(), this.model.id(), this.currentTick, AnimationEventPhase.START);
        if (startAtZero && !this.eventsStopped) execute(timelineEventsAt(0));
    }

    public AdvanceResult advance() { return advance(1, true); }
    public AdvanceResult advance(boolean continueAfterLoopBoundary) { return advance(1, continueAfterLoopBoundary); }
    public AdvanceResult advance(int elapsedTicks) { return advance(elapsedTicks, true); }

    public AdvanceResult advance(int elapsedTicks, boolean continueAfterLoopBoundary) {
        if (elapsedTicks < 0) throw new IllegalArgumentException("Elapsed ticks must be non-negative");
        if ((this.emote instanceof PreparedSequence)) return advanceSequence(elapsedTicks);
        if (this.phase == PlaybackPhase.NOT_STARTED) throw new IllegalStateException("Timeline has not started");
        if (this.phase == PlaybackPhase.FINISHED || this.eventsStopped) return AdvanceResult.FINISHED;
        if (this.phase == PlaybackPhase.LOOP_BOUNDARY) throw new IllegalStateException("Loop boundary must be continued before advancing");
        if (elapsedTicks == 0) return AdvanceResult.CONTINUE;
        this.lifetimeTicks += elapsedTicks;
        this.frameDelta = elapsedTicks;
        this.frameBoundaries = 0;
        int previousTick = this.currentTick;
        int previousLoop = this.loopCount;
        PlaybackPhase previousPhase = this.phase;
        if (this.model.settings().playback().mode() == EmoteAnimation.PlaybackMode.SERVER_SYNC) {
            this.cycleTick += elapsedTicks;
            placeAtCycleTick(this.cycleTick);
            if (this.loopCount - previousLoop > MAX_LOOP_BOUNDARIES_PER_FRAME) throw new IllegalStateException("Server clock crossed too many loop boundaries in one frame");
            if (this.phase == PlaybackPhase.RUNNING || previousPhase != this.phase || this.loopCount != previousLoop) {
                this.evaluator.prepareFrame(this.currentTick, this.loopCount, elapsedTicks, this.lifetimeTicks);
                applyAnimationPose(elapsedTicks, false);
            }
            executeAnimationFrame(previousTick, previousLoop, previousPhase);
            if (this.eventsStopped) return AdvanceResult.FINISHED;
            return this.loopCount != previousLoop ? AdvanceResult.RESTARTED : AdvanceResult.CONTINUE;
        }
        if (this.phase == PlaybackPhase.HOLDING) {
            this.holdTicks++;
            return AdvanceResult.CONTINUE;
        }
        if (this.phase == PlaybackPhase.START_DELAY || this.phase == PlaybackPhase.LOOP_DELAY) {
            this.remainingDelay -= elapsedTicks;
            if (this.remainingDelay > 0) return AdvanceResult.CONTINUE;
            boolean restart = this.phase == PlaybackPhase.LOOP_DELAY;
            if (restart) this.loopCount = Math.incrementExact(this.loopCount);
            this.currentTick = restart ? this.model.settings().playback().loopStartTick() : 0;
            this.phase = PlaybackPhase.RUNNING;
            this.remainingDelay = 0;
            this.evaluator.prepareFrame(this.currentTick, this.loopCount, 0, this.lifetimeTicks);
            applyAnimationPose(0, false);
            executeAnimationFrame(previousTick, previousLoop, previousPhase);
            if (this.eventsStopped) return AdvanceResult.FINISHED;
            return restart ? AdvanceResult.RESTARTED : AdvanceResult.CONTINUE;
        }
        int next = this.evaluator.nextTick(this.currentTick, this.loopCount, elapsedTicks, this.lifetimeTicks);
        AdvanceResult result = resolveAnimationTick(next, continueAfterLoopBoundary);
        this.pendingUpdate = this.phase == PlaybackPhase.LOOP_BOUNDARY && !this.sequenceCycle;
        applyAnimationPose(elapsedTicks, !this.pendingUpdate);
        executeAnimationFrame(previousTick, previousLoop, previousPhase);
        return this.eventsStopped ? AdvanceResult.FINISHED : result;
    }

    private AdvanceResult resolveAnimationTick(int next, boolean continueAfterLoopBoundary) {
        int durationTicks = this.model.clip().durationTicks();
        var playback = this.model.settings().playback();
        while (next >= durationTicks) {
            this.currentTick = durationTicks;
            if (playback.mode() == EmoteAnimation.PlaybackMode.ONCE) {
                this.phase = PlaybackPhase.FINISHED;
                return AdvanceResult.FINISHED;
            }
            if (playback.mode() == EmoteAnimation.PlaybackMode.HOLD) {
                this.phase = PlaybackPhase.HOLDING;
                return AdvanceResult.CONTINUE;
            }
            if (++this.frameBoundaries > MAX_LOOP_BOUNDARIES_PER_FRAME) throw new IllegalStateException("Clock crossed more than " + MAX_LOOP_BOUNDARIES_PER_FRAME + " loop boundaries in one frame");
            this.sampledLoopDelay = this.evaluator.delay(playback.loopDelay(), durationTicks, this.loopCount, this.frameDelta, this.lifetimeTicks);
            this.loopOverflow = next - durationTicks;
            this.phase = PlaybackPhase.LOOP_BOUNDARY;
            if (!continueAfterLoopBoundary) return AdvanceResult.LOOP_BOUNDARY;
            if (this.sampledLoopDelay > 0) {
                this.remainingDelay = this.sampledLoopDelay;
                this.loopOverflow = 0;
                this.phase = PlaybackPhase.LOOP_DELAY;
                return AdvanceResult.CONTINUE;
            }
            this.loopCount = Math.incrementExact(this.loopCount);
            this.phase = PlaybackPhase.RUNNING;
            next = playback.loopStartTick() + this.loopOverflow;
        }
        this.currentTick = next;
        return this.frameBoundaries > 0 ? AdvanceResult.RESTARTED : AdvanceResult.CONTINUE;
    }

    private void applyAnimationPose(int deltaTicks, boolean update) {
        this.evaluator.evaluateFrame(this.currentTick, this.loopCount, deltaTicks, this.lifetimeTicks, update);
        applyEvaluator(deltaTicks == 0 ? 0 : this.evaluator.displayInterpolationTicks());
    }

    private void executeAnimationFrame(int previousTick, int previousLoop, PlaybackPhase previousPhase) {
        if (!this.eventsStarted || this.eventsStopped || this.phase == PlaybackPhase.START_DELAY) return;
        int tick = this.currentTick;
        int loop = this.loopCount;
        PlaybackPhase phase = this.phase;
        if (!this.animationEventsActive) {
            activateAnimationEvents(previousPhase == PlaybackPhase.START_DELAY);
            previousTick = 0;
        }
        if (this.eventsStopped) return;
        int durationTicks = this.model.clip().durationTicks();
        int loopStartTick = this.model.settings().playback().loopStartTick();
        if (previousPhase == PlaybackPhase.LOOP_DELAY || previousPhase == PlaybackPhase.LOOP_BOUNDARY) {
            if (previousLoop == loop) return;
            this.lifecycleListener.onLoop();
            if (this.eventsStopped) return;
            execute(timelineEventsAt(loopStartTick));
            previousLoop++;
            previousTick = loopStartTick;
        }
        while (previousLoop < loop && !this.eventsStopped) {
            execute(timelineEvents(previousTick, durationTicks));
            if (this.eventsStopped) return;
            execute(this.model.clip().events().loop(), this.model.id(), durationTicks, AnimationEventPhase.LOOP);
            if (this.eventsStopped) return;
            this.lifecycleListener.onLoop();
            if (this.eventsStopped) return;
            execute(timelineEventsAt(loopStartTick));
            previousLoop++;
            previousTick = loopStartTick;
        }
        if (this.eventsStopped) return;
        execute(timelineEvents(previousTick, tick));
        if (phase == PlaybackPhase.LOOP_BOUNDARY || phase == PlaybackPhase.LOOP_DELAY) {
            execute(this.model.clip().events().loop(), this.model.id(), durationTicks, AnimationEventPhase.LOOP);
        }
    }

    private SelectedAnimationStep selectedAnimation(PlaybackTimeline.Segment segment) {
        return this.selectedSteps.stream().filter(SelectedAnimationStep.class::isInstance)
            .map(SelectedAnimationStep.class::cast)
            .filter(step -> Objects.equals(step.stepIndex(), segment.stepIndex()) && Objects.equals(step.repeatIndex(), segment.repeatIndex())).findFirst().orElseThrow();
    }

    private void prepareChild(SelectedAnimationStep selected) {
        if (this.child != null && this.childSelection == selected) return;
        this.childSelection = selected;
        this.child = new PlaybackPlayer(selected.animation(), this.querySource);
        this.child.target = this.target;
        this.child.sequenceCycle = true;
        this.child.initializeAnimation();
        if (selected.animation().model().settings().playback().loopDelay() instanceof EmoteAnimation.ConstantValue delay) this.child.sampledLoopDelay = (int) delay.value();
        this.child.evaluator.evaluateFrame(0, 0, 0, 0, false, false);
        this.child.phase = PlaybackPhase.RUNNING;
        for (var segment : this.sequencePhases) {
            if (!Objects.equals(segment.stepIndex(), selected.stepIndex()) || !Objects.equals(segment.repeatIndex(), selected.repeatIndex())) continue;
            if (segment.phase() == PlaybackTimeline.Phase.START_DELAY) this.segmentLengths.put(segment.segmentIndex(), this.child.startDelay);
        }
        if (this.eventExecutor != null) this.child.bindEvents(this.eventExecutor);
    }

    private void enterSequencePhase(boolean replayZero) {
        var templates = this.sequencePhases;
        while (this.sequenceSegment < templates.size() && !this.eventsStopped) {
            var segment = templates.get(this.sequenceSegment);
            this.segmentStarts.put(this.sequenceSegment, this.currentTick);
            this.phaseTick = 0;
            if (segment.phase() == PlaybackTimeline.Phase.WAIT) {
                if (this.child == null) this.emote.nodes().keySet().forEach(id -> applyVisibility(id, false));
                return;
            }
            var selected = selectedAnimation(segment);
            prepareChild(selected);
            if (segment.phase() == PlaybackTimeline.Phase.TRANSITION) {
                this.transitionStartTransforms.clear();
                for (String id : this.child.emote.nodeOrder()) {
                    Matrix4fc start = this.appliedTransforms.get(id);
                    this.transitionStartTransforms.put(id, new Matrix4f(start == null ? this.emote.defaultMatrix(id) : start));
                }
                applyTransition(0);
                return;
            }
            if (segment.phase() == PlaybackTimeline.Phase.START_DELAY || segment.phase() == PlaybackTimeline.Phase.ANIMATION) {
                this.target.resetAll();
                this.appliedTransforms.clear();
                this.appliedVisibility.clear();
                this.child.appliedTransforms.clear();
                this.child.appliedVisibility.clear();
                this.child.appliedAttachmentVisibility.clear();
                this.child.appliedNbt.clear();
                this.child.appliedNbtRemoved.clear();
                this.child.phase = segment.phase() == PlaybackTimeline.Phase.START_DELAY ? PlaybackPhase.START_DELAY : PlaybackPhase.RUNNING;
                this.child.evaluator.prepareFrame(this.child.currentTick, 0, 0, this.child.lifetimeTicks);
                this.child.applyAnimationPose(0, false);
                copyChildPose();
                hideUnusedNodes();
                if (segment.phase() == PlaybackTimeline.Phase.ANIMATION) {
                    startChildEvents(replayZero);
                    return;
                }
            }
            if (segment.phase() == PlaybackTimeline.Phase.LOOP_DELAY) this.segmentLengths.put(this.sequenceSegment, this.child.sampledLoopDelay);
            Integer length = sequencePhaseLength(this.sequenceSegment);
            if (length == null) throw new IllegalStateException("Sequence delay has not been sampled");
            if (length > 0) return;
            this.sequenceSegment++;
        }
        if (!this.eventsStopped) this.phase = PlaybackPhase.FINISHED;
    }

    private @Nullable Integer sequencePhaseLength(int index) {
        if (this.segmentLengths.containsKey(index)) return this.segmentLengths.get(index);
        var segment = this.sequencePhases.get(index);
        if (segment.phase() == PlaybackTimeline.Phase.WAIT) {
            return this.selectedSteps.stream().filter(SelectedWaitStep.class::isInstance)
                .map(SelectedWaitStep.class::cast).filter(wait -> Objects.equals(wait.stepIndex(), segment.stepIndex()))
                .map(SelectedWaitStep::ticks).findFirst().orElse(1);
        }
        var selected = selectedAnimation(segment);
        var playback = selected.animation().model().settings().playback();
        return switch (segment.phase()) {
            case TRANSITION -> selected.transitionTicks();
            case START_DELAY -> playback.startDelay() instanceof EmoteAnimation.ConstantValue c ? (int) c.value() : null;
            case LOOP_DELAY -> playback.loopDelay() instanceof EmoteAnimation.ConstantValue c ? (int) c.value() : null;
            case ANIMATION -> selected.animation().model().clip().clock() == null ? selected.animation().model().clip().durationTicks() : null;
            default -> throw new IllegalStateException("Unexpected sequence phase");
        };
    }

    private void startChildEvents(boolean replayZero) {
        if (!this.eventsStarted || this.eventsStopped || this.child == null || this.child.phase != PlaybackPhase.RUNNING || this.sequencePhases.get(this.sequenceSegment).phase() != PlaybackTimeline.Phase.ANIMATION) return;
        if (!this.child.eventsStarted) {
            this.child.eventsStarted = true;
            this.child.activateAnimationEvents(replayZero && this.child.currentTick == 0);
        }
        if (!this.eventsStopped && !this.childCallbacksStarted) {
            this.childCallbacksStarted = true;
            this.lifecycleListener.onStart((PreparedAnimation) this.child.emote);
        }
    }

    private void closeChild(PlaybackStopReason reason) {
        if (this.child == null) return;
        try {
            this.child.stop(reason);
        } finally {
            if (this.childCallbacksStarted) {
                this.childCallbacksStarted = false;
                this.lifecycleListener.onClose(reason);
            }
        }
    }

    private AdvanceResult advanceSequence(int elapsedTicks) {
        if (this.phase == PlaybackPhase.NOT_STARTED) throw new IllegalStateException("Timeline has not started");
        if (isFinished()) return AdvanceResult.FINISHED;
        int budget = elapsedTicks;
        int boundaries = 0;
        while (budget > 0 && !isFinished()) {
            if (++boundaries > MAX_LOOP_BOUNDARIES_PER_FRAME) throw new IllegalStateException("Sequence crossed too many phases in one frame");
            var segment = this.sequencePhases.get(this.sequenceSegment);
            if (segment.phase() == PlaybackTimeline.Phase.ANIMATION) {
                int delta = this.child.model.clip().clock() == null ? Math.min(budget, Math.max(0, this.child.model.clip().durationTicks() - this.child.currentTick)) : budget;
                this.currentTick += delta;
                this.lifetimeTicks += delta;
                this.phaseTick += delta;
                budget -= delta;
                AdvanceResult result;
                if (delta == 0) {
                    PlaybackPhase previousPhase = this.child.phase;
                    result = this.child.resolveAnimationTick(this.child.currentTick, false);
                    this.child.executeAnimationFrame(this.child.currentTick, this.child.loopCount, previousPhase);
                } else result = this.child.advance(delta, false);
                copyChildPose();
                if (this.eventsStopped) break;
                this.lifecycleListener.onTick(this.child.currentTick);
                if (this.eventsStopped || this.lifecycleListener.hasPendingSeek()) return isFinished() ? AdvanceResult.FINISHED : AdvanceResult.CONTINUE;
                if (result != AdvanceResult.FINISHED && result != AdvanceResult.LOOP_BOUNDARY) break;
                this.segmentLengths.put(this.sequenceSegment, this.phaseTick);
                var selected = selectedAnimation(segment);
                if (selected.loopDelayAfter() && this.child.model.settings().playback().mode() == EmoteAnimation.PlaybackMode.LOOP
                    && this.sequenceSegment + 1 < this.sequencePhases.size()
                    && this.sequencePhases.get(this.sequenceSegment + 1).phase() == PlaybackTimeline.Phase.LOOP_DELAY) {
                    this.segmentLengths.put(this.sequenceSegment + 1, this.child.sampledLoopDelay);
                }
                closeChild(PlaybackStopReason.FINISHED);
                if (this.eventsStopped || this.lifecycleListener.hasPendingSeek()) return isFinished() ? AdvanceResult.FINISHED : AdvanceResult.CONTINUE;
            } else {
                int length = Objects.requireNonNull(sequencePhaseLength(this.sequenceSegment));
                int delta = Math.min(budget, Math.max(0, length - this.phaseTick));
                this.phaseTick += delta;
                this.currentTick += delta;
                this.lifetimeTicks += delta;
                budget -= delta;
                if (this.child != null) this.child.lifetimeTicks += delta;
                if (segment.phase() == PlaybackTimeline.Phase.TRANSITION) applyTransition((double) this.phaseTick / length);
                if (this.phaseTick < length) break;
                this.segmentLengths.put(this.sequenceSegment, length);
            }
            this.sequenceSegment++;
            enterSequencePhase(true);
        }
        return isFinished() ? AdvanceResult.FINISHED : AdvanceResult.CONTINUE;
    }

    private void copyChildPose() {
        this.child.appliedTransforms.forEach((id, matrix) -> this.appliedTransforms.put(id, new Matrix4f(matrix)));
        this.appliedVisibility.putAll(this.child.appliedVisibility);
    }

    private void hideUnusedNodes() {
        for (String id : this.emote.nodes().keySet()) if (!this.child.model.nodes().containsKey(id)) applyVisibility(id, false);
    }

    private void applyTransition(double progress) {
        for (int index = 0; index < this.child.evaluator.nodeCount(); index++) {
            String id = this.child.evaluator.nodeId(index);
            var start = new Transformation(new Matrix4f(this.transitionStartTransforms.get(id)));
            var end = new Transformation(new Matrix4f(this.child.evaluator.matrix(index)));
            applyTransform(id, start.slerp(end, (float) progress).getMatrix(), 0);
            applyVisibility(id, this.child.evaluator.visible(index));
            this.child.evaluator.attachmentVisibility(index).forEach((attachment, visible) -> this.target.setVisible(id, attachment, visible));
        }
        hideUnusedNodes();
    }

    public AdvanceResult continueAfterLoopEvent() {
        if (this.phase != PlaybackPhase.LOOP_BOUNDARY) throw new IllegalStateException("Timeline is not at a loop boundary");
        int previousTick = this.currentTick;
        int previousLoop = this.loopCount;
        PlaybackPhase previousPhase = this.phase;
        AdvanceResult result;
        if (this.sampledLoopDelay > 0) {
            this.remainingDelay = this.sampledLoopDelay;
            this.loopOverflow = 0;
            this.phase = PlaybackPhase.LOOP_DELAY;
            result = AdvanceResult.CONTINUE;
        } else {
            this.loopCount = Math.incrementExact(this.loopCount);
            this.phase = PlaybackPhase.RUNNING;
            result = resolveAnimationTick(this.model.settings().playback().loopStartTick() + this.loopOverflow, true);
            if (result == AdvanceResult.CONTINUE && this.phase == PlaybackPhase.RUNNING) result = AdvanceResult.RESTARTED;
        }
        applyAnimationPose(this.frameDelta, this.pendingUpdate);
        this.pendingUpdate = false;
        executeAnimationFrame(previousTick, previousLoop, previousPhase);
        return this.eventsStopped ? AdvanceResult.FINISHED : result;
    }

    public int currentTick() { return this.currentTick; }

    public boolean canSeek() {
        return this.phase != PlaybackPhase.NOT_STARTED && !this.eventsStopped && this.emote.playbackMode() != EmoteAnimation.PlaybackMode.SERVER_SYNC;
    }

    public boolean canSetTick(int tick) {
        if (tick < 0) throw new IllegalArgumentException("Tick must be non-negative");
        if ((this.emote instanceof PreparedSequence)) locateSequence(tick);
        else if (tick > this.model.clip().durationTicks()) throw new IllegalArgumentException("Time is outside the animation");
        return canSeek();
    }

    public void setTick(int tick) {
        if (!canSetTick(tick)) return;
        if ((this.emote instanceof PreparedSequence)) {
            seekSequence(locateSequence(tick), tick, null);
            return;
        }
        this.target.resetAll();
        clearState();
        this.currentTick = tick;
        this.phase = PlaybackPhase.RUNNING;
        this.holdTicks = 0;
        this.evaluator.prepareFrame(tick, this.loopCount, 0, this.lifetimeTicks);
        applyAnimationPose(0, false);
        activateAnimationEvents(false);
    }

    public boolean canSetAnimationTick(int animationTick) {
        PlaybackPlayer animation = (this.emote instanceof PreparedSequence) ? this.child : this;
        if (animationTick < 0 || (animation != null && animationTick > animation.model.clip().durationTicks())) throw new IllegalArgumentException("Tick is outside the animation");
        return canSeek() && position().phase() == PlaybackTimeline.Phase.ANIMATION;
    }

    public void setAnimationTick(int animationTick) {
        if (!canSetAnimationTick(animationTick)) return;
        if (!(this.emote instanceof PreparedSequence)) { setTick(animationTick); return; }
        this.child.setTick(animationTick);
        if (this.child.model.clip().clock() == null) this.segmentLengths.put(this.sequenceSegment, this.phaseTick + this.child.model.clip().durationTicks() - animationTick);
        else this.segmentLengths.remove(this.sequenceSegment);
        this.segmentStarts.keySet().removeIf(index -> index > this.sequenceSegment);
        this.segmentLengths.keySet().removeIf(index -> index > this.sequenceSegment);
        this.appliedTransforms.clear();
        this.appliedVisibility.clear();
        copyChildPose();
        hideUnusedNodes();
        this.lifecycleListener.onPositionChanged(animationTick);
    }

    public int stepSegment(int stepIndex, int repeatIndex, int animationTick) {
        if (stepIndex < 0 || repeatIndex < 0 || animationTick < 0) throw new IllegalArgumentException("Step, repeat and tick must not be negative");
        for (var segment : this.sequencePhases) {
            if (segment.phase() != PlaybackTimeline.Phase.ANIMATION || !Objects.equals(segment.stepIndex(), stepIndex) || !Objects.equals(segment.repeatIndex(), repeatIndex)) continue;
            if (animationTick > selectedAnimation(segment).animation().model().clip().durationTicks()) throw new IllegalArgumentException("Time is outside the animation");
            return segment.segmentIndex();
        }
        throw new IllegalArgumentException("No animation at the specified sequence step and repeat");
    }

    public void setStep(int stepIndex, int repeatIndex, int animationTick) {
        int index = stepSegment(stepIndex, repeatIndex, animationTick);
        if (!canSeek()) return;
        var segment = timeline().segments().get(index);
        boolean dynamicClock = selectedAnimation(segment).animation().model().clip().clock() != null;
        int location = dynamicClock || segment.startTick() == null ? this.currentTick : segment.startTick() + animationTick;
        seekSequence(index, location, animationTick);
    }

    private int locateSequence(int tick) {
        var segments = timeline().segments();
        for (var segment : segments) {
            if (segment.startTick() == null || tick < segment.startTick()) continue;
            if (segment.endTick() == null || tick >= segment.endTick()) continue;
            if (segment.phase() == PlaybackTimeline.Phase.ANIMATION && selectedAnimation(segment).animation().model().clip().clock() != null) {
                throw new IllegalArgumentException("A dynamic clock requires animation time or step seek");
            }
            if (segment.phase() == PlaybackTimeline.Phase.WAIT || segment.phase() == PlaybackTimeline.Phase.TRANSITION || segment.phase() == PlaybackTimeline.Phase.LOOP_DELAY) {
                int previous = segment.segmentIndex() - 1;
                while (previous >= 0 && segments.get(previous).phase() != PlaybackTimeline.Phase.ANIMATION) previous--;
                if (previous >= 0) {
                    var source = selectedAnimation(segments.get(previous));
                    if (this.child == null || this.childSelection != source) {
                        var model = source.animation().model();
                        if (model.molang().update() != null || model.clip().clock() != null
                            || segment.phase() == PlaybackTimeline.Phase.LOOP_DELAY && model.settings().playback().loopDelay() instanceof EmoteAnimation.MolangValue) {
                            throw new IllegalArgumentException("Cannot reconstruct a previous animation program for this sequence gap");
                        }
                    }
                }
            }
            return segment.segmentIndex();
        }
        throw new IllegalArgumentException("Sequence location is outside the known timeline");
    }

    private void seekSequence(int destination, int location, @Nullable Integer animationTick) {
        var segments = timeline().segments();
        var segment = segments.get(destination);
        boolean sameExecution = this.child != null && !this.child.eventsStopped && segment.animationId() != null && this.childSelection == selectedAnimation(segment);
        if (sameExecution && destination == this.sequenceSegment && segment.phase() == PlaybackTimeline.Phase.TRANSITION) {
            this.phaseTick = location - Objects.requireNonNull(segment.startTick());
            this.currentTick = location;
            applyTransition((double) this.phaseTick / Objects.requireNonNull(sequencePhaseLength(destination)));
            return;
        }
        if (!sameExecution) closeChild(PlaybackStopReason.REPLACED);
        if (this.eventsStopped) return;
        int local = animationTick != null ? animationTick : location - Objects.requireNonNull(segment.startTick());
        boolean dynamicClock = segment.phase() == PlaybackTimeline.Phase.ANIMATION && selectedAnimation(segment).animation().model().clip().clock() != null;
        int elapsed = dynamicClock ? sameExecution ? this.phaseTick : 0 : local;
        this.segmentStarts.keySet().removeIf(index -> index >= destination);
        this.segmentLengths.keySet().removeIf(index -> index >= destination);
        if (!sameExecution && segment.phase() != PlaybackTimeline.Phase.ANIMATION && segment.phase() != PlaybackTimeline.Phase.START_DELAY) {
            int previous = destination - 1;
            while (previous >= 0 && segments.get(previous).phase() != PlaybackTimeline.Phase.ANIMATION) previous--;
            if (previous >= 0) {
                var source = selectedAnimation(segments.get(previous));
                if (this.child == null || this.childSelection != source) {
                    if (source.animation().model().molang().update() != null || source.animation().model().clip().clock() != null) throw new IllegalArgumentException("Cannot reconstruct a previous animation program for this sequence gap");
                    this.child = null;
                    prepareChild(source);
                }
                this.target.resetAll();
                this.appliedTransforms.clear();
                this.appliedVisibility.clear();
                this.child.clearState();
                this.child.currentTick = source.animation().model().clip().durationTicks();
                this.child.evaluator.prepareFrame(this.child.currentTick, 0, 0, this.child.lifetimeTicks);
                this.child.applyAnimationPose(0, false);
                copyChildPose();
                hideUnusedNodes();
            } else {
                this.child = null;
                this.appliedTransforms.clear();
                this.emote.nodes().keySet().forEach(id -> applyVisibility(id, false));
            }
        }
        if (!sameExecution && (segment.phase() == PlaybackTimeline.Phase.ANIMATION || segment.phase() == PlaybackTimeline.Phase.START_DELAY || segment.phase() == PlaybackTimeline.Phase.TRANSITION)) {
            this.child = null;
        }
        this.sequenceSegment = destination;
        this.currentTick = location - elapsed;
        this.phase = PlaybackPhase.RUNNING;
        if (segment.phase() == PlaybackTimeline.Phase.ANIMATION) {
            var selected = selectedAnimation(segment);
            if (!sameExecution) {
                prepareChild(selected);
            }
            this.segmentStarts.put(destination, this.currentTick);
            this.phaseTick = elapsed;
            this.currentTick = location;
            this.child.phase = PlaybackPhase.RUNNING;
            this.child.setTick(local);
            this.appliedTransforms.clear();
            this.appliedVisibility.clear();
            copyChildPose();
            hideUnusedNodes();
            this.lifecycleListener.onPositionChanged(local);
            startChildEvents(false);
        } else {
            enterSequencePhase(false);
            this.phaseTick = local;
            this.currentTick = location;
            if (segment.phase() == PlaybackTimeline.Phase.TRANSITION) applyTransition((double) local / Objects.requireNonNull(sequencePhaseLength(destination)));
        }
    }

    public boolean isFinished() { return this.phase == PlaybackPhase.FINISHED || this.eventsStopped; }

    public PlaybackTimeline timeline() {
        if (!(this.emote instanceof PreparedSequence)) {
            Integer delay = this.model.settings().playback().loopDelay() instanceof EmoteAnimation.ConstantValue constant ? (int) constant.value() : null;
            return animationTimeline(this.model, this.startDelay, delay);
        }
        Integer offset = 0;
        List<PlaybackTimeline.Segment> segments = new ArrayList<>();
        for (var segment : this.sequencePhases) {
            offset = this.segmentStarts.getOrDefault(segment.segmentIndex(), offset);
            Integer length = sequencePhaseLength(segment.segmentIndex());
            Integer end = offset == null || length == null ? null : Math.addExact(offset, length);
            segments.add(new PlaybackTimeline.Segment(segment.segmentIndex(), segment.stepIndex(), segment.repeatIndex(), segment.phase(), offset, end, segment.animationId()));
            offset = end;
        }
        return new PlaybackTimeline(Identifier.parse(this.emote.id()), offset, EmoteAnimation.PlaybackMode.ONCE, 0, segments);
    }

    public PlaybackPosition position() {
        if (this.stoppedPosition != null) return this.stoppedPosition;
        var segments = timeline().segments();
        if ((this.emote instanceof PreparedSequence)) {
            var selected = segments.get(Math.min(this.sequenceSegment, segments.size() - 1));
            return new PlaybackPosition(selected.segmentIndex(), selected.stepIndex(), selected.repeatIndex(), selected.phase(), this.phaseTick,
                selected.animationId(), selected.phase() == PlaybackTimeline.Phase.ANIMATION ? this.child.currentTick : null);
        }
        PlaybackTimeline.Phase phase = switch (this.phase) {
            case START_DELAY -> PlaybackTimeline.Phase.START_DELAY;
            case LOOP_DELAY -> PlaybackTimeline.Phase.LOOP_DELAY;
            case HOLDING -> PlaybackTimeline.Phase.HOLD;
            default -> PlaybackTimeline.Phase.ANIMATION;
        };
        var selected = segments.stream().filter(segment -> segment.phase() == phase).findFirst().orElseThrow();
        int phaseTick = switch (this.phase) {
            case START_DELAY -> this.startDelay - this.remainingDelay;
            case LOOP_DELAY -> this.sampledLoopDelay - this.remainingDelay;
            case HOLDING -> Math.toIntExact(this.holdTicks);
            default -> this.currentTick;
        };
        return new PlaybackPosition(selected.segmentIndex(), null, null, phase, phaseTick, selected.animationId(),
            phase == PlaybackTimeline.Phase.ANIMATION ? this.currentTick : null);
    }

    public Identifier emoteId() {
        return Identifier.parse(this.emote.id());
    }

    public float rotationDeadzone() {
        return this.child == null ? this.emote.rotationDeadzone() : this.child.model.settings().rotationDeadzone();
    }

    public void stop() {
        stop(PlaybackStopReason.MANUAL);
    }

    public void stop(PlaybackStopReason reason) {
        if (this.stoppedPosition == null) this.stoppedPosition = position();
        this.phase = PlaybackPhase.FINISHED;
        if (!this.eventsStarted || this.eventsStopped) {
            return;
        }
        this.eventsStopped = true;
        if (!(this.emote instanceof PreparedSequence)) {
            if (!this.animationEventsActive) return;
            execute(
                this.model.clip().events().stop(),
                this.model.id(),
                this.currentTick,
                AnimationEventPhase.STOP
            );
            return;
        }
        closeChild(reason);
    }

    public Transformation currentTransformation(String nodeId) {
        if ((this.emote instanceof PreparedSequence) && this.child != null && position().phase() == PlaybackTimeline.Phase.ANIMATION && this.child.model.nodes().containsKey(nodeId)) return this.child.currentTransformation(nodeId);
        Matrix4fc matrix = this.appliedTransforms.get(nodeId);
        if (matrix == null) return this.target.createTransformation(nodeId, this.emote.defaultMatrix(nodeId));
        return this.target.createTransformation(nodeId, matrix);
    }

    private void clearState() {
        this.transitionStartTransforms.clear();
        this.appliedTransforms.clear();
        this.appliedVisibility.clear();
        this.appliedAttachmentVisibility.clear();
        this.appliedNbt.clear();
        this.appliedNbtRemoved.clear();
        this.currentTick = 0;
        this.remainingDelay = 0;
        this.loopOverflow = 0;
        this.pendingUpdate = false;
    }

    private void applyEvaluator(int interpolationDurationTicks) {
        for (int index = 0; index < this.evaluator.nodeCount(); index++) {
            String nodeId = applyEvaluatorTransform(index, interpolationDurationTicks);
            applyVisibility(nodeId, this.evaluator.visible(index));
            Map<String, Boolean> previous = this.appliedAttachmentVisibility.computeIfAbsent(nodeId, ignored -> new HashMap<>());
            this.evaluator.attachmentVisibility(index).forEach((attachmentId, visible) -> {
                if (!Boolean.valueOf(visible).equals(previous.put(attachmentId, visible))) {
                    this.target.setVisible(nodeId, attachmentId, visible);
                }
            });
            for (var attachment : this.evaluator.nbt(index).entrySet()) {
                applyNbt(nodeId, attachment.getKey(), attachment.getValue(), this.evaluator.nbtRemoved(index, attachment.getKey()));
            }
        }
    }

    private String applyEvaluatorTransform(int index, int interpolationDurationTicks) {
        String nodeId = this.evaluator.nodeId(index);
        applyTransform(
            nodeId,
            this.evaluator.matrix(index),
            interpolationDurationTicks
        );
        return nodeId;
    }

    private void applyTransform(
        String nodeId,
        Matrix4fc matrix,
        int interpolationDurationTicks
    ) {
        Matrix4f applied = this.appliedTransforms.get(nodeId);
        if (applied != null && matrix.equals(applied)) return;
        if (applied == null) this.appliedTransforms.put(nodeId, new Matrix4f(matrix));
        else applied.set(matrix);
        this.target.applyTransform(nodeId, matrix, interpolationDurationTicks);
    }

    private void applyVisibility(String nodeId, boolean visible) {
        if (Boolean.valueOf(visible).equals(this.appliedVisibility.put(nodeId, visible))) {
            return;
        }
        this.target.setVisible(nodeId, visible);
    }

    private void applyNbt(String nodeId, String attachmentId, CompoundTag nbt, Set<String> remove) {
        Map<String, CompoundTag> previous = this.appliedNbt.computeIfAbsent(nodeId, ignored -> new HashMap<>());
        Map<String, Set<String>> previousRemoved = this.appliedNbtRemoved.computeIfAbsent(nodeId, ignored -> new HashMap<>());
        if (nbt.equals(previous.get(attachmentId)) && remove.equals(previousRemoved.get(attachmentId))) return;
        previous.put(attachmentId, nbt.copy());
        previousRemoved.put(attachmentId, Set.copyOf(remove));
        this.target.applyNbt(nodeId, attachmentId, nbt, remove);
    }

    public enum AdvanceResult {
        CONTINUE,
        LOOP_BOUNDARY,
        RESTARTED,
        FINISHED
    }

    private enum PlaybackPhase {
        NOT_STARTED,
        START_DELAY,
        RUNNING,
        LOOP_BOUNDARY,
        LOOP_DELAY,
        HOLDING,
        FINISHED
    }

    private void execute(
        List<EmoteAnimation.Event> events,
        Identifier animationId,
        int animationTick,
        AnimationEventPhase phase
    ) {
        execute(events.stream().map(event -> new PlaybackPlayer.EventOccurrence(event, animationId, animationTick, phase)).toList());
    }

    private void execute(List<PlaybackPlayer.EventOccurrence> events) {
        if (this.eventExecutor == null) {
            return;
        }
        for (PlaybackPlayer.EventOccurrence event : events) {
            if (this.eventsStopped && event.phase() != AnimationEventPhase.STOP) break;
            this.eventExecutor.execute(event);
        }
    }

    @FunctionalInterface
    public interface EventExecutor {
        void execute(PlaybackPlayer.EventOccurrence event);
    }

    public interface LifecycleListener {
        default void onStart(PreparedAnimation animation) {}
        default void onTick(int animationTick) {}
        default void onLoop() {}
        default void onClose(PlaybackStopReason reason) {}
        default void onPositionChanged(int animationTick) {}
        default boolean hasPendingSeek() { return false; }
    }

    public interface TimelineTarget {
        Transformation createTransformation(String nodeId, Matrix4fc matrix);

        void applyTransform(String nodeId, Matrix4fc matrix, int interpolationDurationTicks);

        void setVisible(String nodeId, boolean visible);

        void setVisible(String nodeId, String attachmentId, boolean visible);

        void applyNbt(String nodeId, String attachmentId, CompoundTag nbt, Set<String> remove);

        default void resetNbt(String nodeId, String attachmentId) {}

        void resetAll();
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
            AnimationStep animationStep = (AnimationStep) step;
            List<SelectedAnimationStep> selectedAnimations = new ArrayList<>();
            int animationChoiceCount = (int) animationStep.choices().stream().filter(AnimationChoice.class::isInstance).count();
            int previousAnimationIndex = -1;
            for (int repeat = 0; repeat < animationStep.repeat(); repeat++) {
                int excludedIndex = animationChoiceCount > 1 ? previousAnimationIndex : -1;
                int selectedIndex = WeightedChoiceSelector.selectIndex(random, animationStep.choices(), Choice::chance, excludedIndex);
                Choice selected = animationStep.choices().get(selectedIndex);
                if (selected instanceof AnimationChoice animation) {
                    selectedAnimations.add(new SelectedAnimationStep(animation.animation(), false, animationStep.transitionTicks(), stepIndex, repeat));
                    previousAnimationIndex = selectedIndex;
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

    private static List<PlaybackTimeline.Segment> sequencePhases(List<SelectedStep> steps) {
        List<PlaybackTimeline.Segment> segments = new ArrayList<>();
        boolean previousPose = false;
        for (var step : steps) {
            if (step instanceof SelectedWaitStep wait) {
                segments.add(new PlaybackTimeline.Segment(segments.size(), wait.stepIndex(), null, PlaybackTimeline.Phase.WAIT, 0, wait.ticks(), null));
                continue;
            }
            var selected = (SelectedAnimationStep) step;
            var animation = selected.animation();
            var playback = animation.model().settings().playback();
            if (previousPose && selected.transitionTicks() > 0) addSequencePhase(segments, selected, PlaybackTimeline.Phase.TRANSITION, selected.transitionTicks());
            Integer startDelay = playback.startDelay() instanceof EmoteAnimation.ConstantValue constant ? (int) constant.value() : null;
            if (startDelay == null || startDelay > 0) addSequencePhase(segments, selected, PlaybackTimeline.Phase.START_DELAY, startDelay);
            addSequencePhase(segments, selected, PlaybackTimeline.Phase.ANIMATION, animation.model().clip().clock() == null ? animation.model().clip().durationTicks() : null);
            if (selected.loopDelayAfter() && playback.mode() == EmoteAnimation.PlaybackMode.LOOP) {
                Integer delay = playback.loopDelay() instanceof EmoteAnimation.ConstantValue constant ? (int) constant.value() : null;
                if (delay == null || delay > 0) addSequencePhase(segments, selected, PlaybackTimeline.Phase.LOOP_DELAY, delay);
            }
            previousPose = true;
        }
        if (segments.isEmpty()) segments.add(new PlaybackTimeline.Segment(0, null, null, PlaybackTimeline.Phase.WAIT, 0, 1, null));
        return List.copyOf(segments);
    }

    private static void addSequencePhase(List<PlaybackTimeline.Segment> segments, SelectedAnimationStep selected, PlaybackTimeline.Phase phase, @Nullable Integer length) {
        segments.add(new PlaybackTimeline.Segment(segments.size(), selected.stepIndex(), selected.repeatIndex(), phase, 0, length, phase == PlaybackTimeline.Phase.ANIMATION || phase == PlaybackTimeline.Phase.TRANSITION ? selected.animation().model().id() : null));
    }

    private static PlaybackTimeline animationTimeline(EmoteAnimation animation, @Nullable Integer startDelay, @Nullable Integer loopDelay) {
        int durationTicks = animation.clip().durationTicks();
        var playback = animation.settings().playback();
        List<PlaybackTimeline.Segment> segments = new ArrayList<>();
        if (startDelay == null || startDelay > 0) segments.add(new PlaybackTimeline.Segment(0, null, null, PlaybackTimeline.Phase.START_DELAY, 0, startDelay, null));
        Integer end = startDelay == null || animation.clip().clock() != null ? null : Math.addExact(startDelay, durationTicks);
        segments.add(new PlaybackTimeline.Segment(segments.size(), null, null, PlaybackTimeline.Phase.ANIMATION, startDelay, end, animation.id()));
        if ((playback.mode() == EmoteAnimation.PlaybackMode.LOOP || playback.mode() == EmoteAnimation.PlaybackMode.SERVER_SYNC) && (loopDelay == null || loopDelay > 0)) {
            segments.add(new PlaybackTimeline.Segment(segments.size(), null, null, PlaybackTimeline.Phase.LOOP_DELAY, end, end == null || loopDelay == null ? null : end + loopDelay, null));
        } else if (playback.mode() == EmoteAnimation.PlaybackMode.HOLD) {
            segments.add(new PlaybackTimeline.Segment(segments.size(), null, null, PlaybackTimeline.Phase.HOLD, end, null, animation.id()));
        }
        return new PlaybackTimeline(animation.id(), animation.clip().clock() == null ? durationTicks : null, playback.mode(), playback.loopStartTick(), segments);
    }

    private List<EventOccurrence> timelineEvents(int previousTick, int currentTick) {
        if (previousTick == currentTick) return List.of();
        boolean forward = currentTick > previousTick;
        Comparator<EmoteAnimation.TimelineEvent> order = Comparator.comparingDouble(EmoteAnimation.TimelineEvent::tick);
        return this.model.clip().events().timeline().stream()
            .filter(event -> forward ? event.tick() > previousTick && event.tick() <= currentTick && event.direction() != EmoteAnimation.Direction.BACKWARD
                : event.tick() >= currentTick && event.tick() < previousTick && event.direction() != EmoteAnimation.Direction.FORWARD)
            .sorted(forward ? order : order.reversed())
            .map(event -> new EventOccurrence(event.event(), this.model.id(), event.tick(), AnimationEventPhase.TIMELINE)).toList();
    }

    private List<EventOccurrence> timelineEventsAt(int tick) {
        return this.model.clip().events().timeline().stream()
            .filter(event -> event.tick() == tick && event.direction() != EmoteAnimation.Direction.BACKWARD)
            .map(event -> new EventOccurrence(event.event(), this.model.id(), event.tick(), AnimationEventPhase.TIMELINE)).toList();
    }

    public record EventOccurrence(
        EmoteAnimation.Event event,
        Identifier animationId,
        int animationTick,
        AnimationEventPhase phase
    ) {
        public EventOccurrence {
            Objects.requireNonNull(event, "event");
            Objects.requireNonNull(animationId, "animationId");
            Objects.requireNonNull(phase, "phase");
        }
    }

    private sealed interface SelectedStep permits SelectedAnimationStep, SelectedWaitStep {
    }

    private record SelectedAnimationStep(
        PreparedAnimation animation,
        boolean loopDelayAfter,
        int transitionTicks,
        int stepIndex,
        int repeatIndex
    ) implements SelectedStep {
        private SelectedAnimationStep {
            Objects.requireNonNull(animation, "animation");
            if (transitionTicks < 0) {
                throw new IllegalArgumentException("sequence transition must not be negative");
            }
        }
    }

    private record SelectedWaitStep(int ticks, int stepIndex) implements SelectedStep {
        private SelectedWaitStep {
            if (ticks < 1) {
                throw new IllegalArgumentException("sequence wait must be at least 1 tick");
            }
        }
    }

}
