package io.github.hanhy06.emote.playback;

import com.mojang.math.Transformation;
import io.github.hanhy06.emote.api.PlaybackPosition;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.api.PlaybackTimeline;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.api.sequence.EmoteSequence;
import io.github.hanhy06.emote.content.AnimationEventPhase;
import io.github.hanhy06.emote.content.PreparedAnimation;
import io.github.hanhy06.emote.content.PreparedEmote;
import io.github.hanhy06.emote.content.PreparedSequence;
import io.github.hanhy06.emote.content.PreparedSequence.*;
import io.github.hanhy06.emote.playback.molang.MolangQuerySource;
import io.github.hanhy06.emote.util.WeightedChoiceSelector;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

public final class EmotePlayback {
    private final EmoteAnimation model;
    private final PreparedEmote emote;
    private final List<SelectedStep> selectedSteps;
    private final List<PlaybackTimeline.Segment> sequencePhases;
    private PoseTarget target;
    private final MolangQuerySource querySource;
    private final AnimationEvaluator evaluator;
    private @Nullable Frame preparingFrame, pendingFrame;
    private @Nullable PoseView notificationView;
    private @Nullable PoseView appliedPose;
    private @Nullable EmoteAnimation eventAnimation;
    private @Nullable PoseView eventPose;
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
    private EmotePlayback child;
    private SelectedAnimationStep childSelection;
    private int sequenceSegment;
    private int phaseTick;
    private boolean childCallbacksStarted;
    private boolean sequenceCycle;
    private final Map<Integer, Integer> segmentLengths = new HashMap<>();
    private final Map<Integer, Integer> segmentStarts = new HashMap<>();
    private PlaybackPhase phase = PlaybackPhase.NOT_STARTED;
    private EventExecutor eventExecutor;
    private boolean eventsStarted;
    private boolean animationEventsActive;
    private boolean eventsStopped;
    private LifecycleListener lifecycleListener = new LifecycleListener() {};
    private long holdTicks;
    private PlaybackPosition stoppedPosition;
    private Map<String, Supplier<Matrix4fc>> transitionStartTransforms = new HashMap<>();

    public EmotePlayback(PreparedEmote emote) {
        this(emote, MolangQuerySource.EMPTY, RandomGenerator.getDefault());
    }

    public EmotePlayback(PreparedEmote emote, MolangQuerySource querySource) {
        this(emote, querySource, RandomGenerator.getDefault());
    }

    public EmotePlayback(PreparedEmote emote, MolangQuerySource querySource, RandomGenerator random) {
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

    public Frame prepareStart(PoseTarget target) {
        if (this.phase != PlaybackPhase.NOT_STARTED) throw new IllegalStateException("Timeline already started");
        this.target = Objects.requireNonNull(target, "target");
        return prepare(() -> {
            start();
            startEvents();
            return AdvanceResult.CONTINUE;
        });
    }

    public Frame prepareStartSynchronized(PoseTarget target, long serverTick) {
        if (this.emote.playbackMode() != EmoteAnimation.PlaybackMode.SERVER_SYNC) throw new IllegalStateException("Timeline is not server synchronized");
        return prepareStartAtCyclePhase(target, serverTick);
    }

    public Frame prepareStartAtCyclePhase(PoseTarget target, long cycleTick) {
        if (this.phase != PlaybackPhase.NOT_STARTED) throw new IllegalStateException("Timeline already started");
        this.target = Objects.requireNonNull(target, "target");
        return prepare(() -> {
            startAtCyclePhaseUnchecked(cycleTick);
            startEvents();
            return AdvanceResult.CONTINUE;
        });
    }

    public Frame prepareAdvance() { return prepareAdvance(1); }
    public Frame prepareAdvance(int elapsedTicks) { return prepare(() -> advance(elapsedTicks, true)); }
    public Frame prepareTick(int tick) { return prepare(() -> { setTick(tick); return AdvanceResult.CONTINUE; }); }
    public Frame prepareAnimationTick(int tick) { return prepare(() -> { setAnimationTick(tick); return AdvanceResult.CONTINUE; }); }
    public Frame prepareStep(int step, int repeat, int tick) { return prepare(() -> { setStep(step, repeat, tick); return AdvanceResult.CONTINUE; }); }
    public Frame prepareStop(PlaybackStopReason reason) {
        return prepare(() -> {
            this.preparingFrame.fixedView = this.eventPose == null ? this.appliedPose : this.eventPose;
            this.stoppedPosition = this.appliedPose == null ? null : this.appliedPose.position();
            this.phase = PlaybackPhase.FINISHED;
            this.eventsStopped = true;
            if (this.eventAnimation == null) return AdvanceResult.FINISHED;
            PlaybackPosition position = this.eventPose.position();
            EmoteAnimation animation = this.eventAnimation;
            this.eventAnimation = null;
            execute(animation.clip().events().stop(), animation.id(), position.animationTick() == null ? this.eventPose.tick() : position.animationTick(), AnimationEventPhase.STOP);
            if (this.emote instanceof PreparedSequence) notifyLifecycle(listener -> listener.onClose(reason), true);
            return AdvanceResult.FINISHED;
        });
    }

    private Frame prepare(Supplier<AdvanceResult> preparation) {
        if (this.pendingFrame != null) throw new IllegalStateException("Playback frame has not been applied or discarded");
        Frame frame = new Frame();
        this.preparingFrame = frame;
        frame.playbacks.add(this);
        if (this.child != null) this.child.preparingFrame = frame;
        if (this.child != null) frame.playbacks.add(this.child);
        this.pendingFrame = frame;
        try {
            frame.result = preparation.get();
            frame.finalPose = frame.view();
            frame.timeline = timeline();
            return frame;
        } catch (RuntimeException | Error exception) {
            frame.discard();
            throw exception;
        } finally {
            for (EmotePlayback playback : frame.playbacks) playback.preparingFrame = null;
        }
    }

    private void start() {
        if (this.phase != PlaybackPhase.NOT_STARTED) throw new IllegalStateException("Timeline already started");
        this.phase = PlaybackPhase.RUNNING;
        resetTarget();
        if (this.emote instanceof PreparedSequence) {
            enterSequencePhase(true);
        } else {
            initializeAnimation();
            this.phase = this.startDelay > 0 ? PlaybackPhase.START_DELAY : PlaybackPhase.RUNNING;
            this.remainingDelay = this.startDelay;
            applyAnimationPose(0, false);
        }
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
            || this.model.clip().tracks().stream().flatMap(track -> track.driver().keys().stream()).anyMatch(key -> key.post() instanceof EmoteAnimation.MolangNbtValue)) {
            throw new IllegalStateException("Cycle phase placement requires a reconstructible clock and fixed delays");
        }
        resetTarget();
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

    private void startEvents() {
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
        recordEventPose();
        execute(this.model.clip().events().start(), this.model.id(), this.currentTick, AnimationEventPhase.START);
        if (startAtZero && !this.eventsStopped) execute(timelineEventsAt(0));
    }

    private AdvanceResult advance(int elapsedTicks, boolean continueAfterLoopBoundary) {
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
                return advanceAnimationPose(elapsedTicks, false, previousTick, previousLoop, previousPhase,
                    this.loopCount != previousLoop ? AdvanceResult.RESTARTED : AdvanceResult.CONTINUE);
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
            return advanceAnimationPose(0, false, previousTick, previousLoop, previousPhase,
                restart ? AdvanceResult.RESTARTED : AdvanceResult.CONTINUE);
        }
        int next = this.evaluator.nextTick(this.currentTick, this.loopCount, elapsedTicks, this.lifetimeTicks);
        AdvanceResult result = resolveAnimationTick(next, continueAfterLoopBoundary);
        this.pendingUpdate = this.phase == PlaybackPhase.LOOP_BOUNDARY && !this.sequenceCycle;
        return advanceAnimationPose(elapsedTicks, !this.pendingUpdate, previousTick, previousLoop, previousPhase, result);
    }

    private AdvanceResult advanceAnimationPose(int deltaTicks, boolean update, int previousTick, int previousLoop,
                                               PlaybackPhase previousPhase, AdvanceResult result) {
        this.evaluator.sampleFrame(this.currentTick, this.loopCount, deltaTicks, this.lifetimeTicks, update, true);
        this.evaluator.finishFrame();
        preparePose(this.evaluator, null, 0, deltaTicks == 0 ? 0 : 1, true, false);
        executeAnimationFrame(previousTick, previousLoop, previousPhase);
        return result;
    }

    private void preparePose(AnimationEvaluator evaluator, @Nullable Map<String, Supplier<Matrix4fc>> transitionStart,
                             float progress, int interpolationTicks, boolean apply, boolean transition) {
        Frame frame = Objects.requireNonNull(this.preparingFrame, "No frame is being prepared");
        PoseStep step = new PoseStep(evaluator, evaluator.capturePose(), transitionStart, progress);
        frame.poses.add(step);
        if (!apply) return;
        for (int index = 0; index < evaluator.nodeCount(); index++) {
            int nodeIndex = index;
            frame.nodeMatrices.put(evaluator.nodeId(index), () -> step.pose.matrices[nodeIndex]);
        }
        frame.applications.add(() -> {
            if (transition) applyTransitionPose(step);
            else applyEvaluator(step, interpolationTicks);
        });
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
        this.evaluator.sampleFrame(this.currentTick, this.loopCount, deltaTicks, this.lifetimeTicks, update, true);
        this.evaluator.finishFrame();
        preparePose(this.evaluator, null, 0, deltaTicks == 0 ? 0 : 1, true, false);
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
        recordEventPose();
        int durationTicks = this.model.clip().durationTicks();
        int loopStartTick = this.model.settings().playback().loopStartTick();
        if (previousPhase == PlaybackPhase.LOOP_DELAY || previousPhase == PlaybackPhase.LOOP_BOUNDARY) {
            if (previousLoop == loop) return;
            notifyLifecycle(LifecycleListener::onLoop, false);
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
            notifyLifecycle(LifecycleListener::onLoop, false);
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
        this.child = new EmotePlayback(selected.animation(), this.querySource, RandomGenerator.getDefault());
        this.child.target = this.target;
        this.child.preparingFrame = this.preparingFrame;
        this.preparingFrame.playbacks.add(this.child);
        this.child.sequenceCycle = true;
        this.child.initializeAnimation();
        if (selected.animation().model().settings().playback().loopDelay() instanceof EmoteAnimation.ConstantValue delay) this.child.sampledLoopDelay = (int) delay.value();
        this.child.evaluator.sampleFrame(0, 0, 0, 0, false, false);
        this.child.preparePose(this.child.evaluator, null, 0, 0, false, false);
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
                this.transitionStartTransforms = new HashMap<>();
                for (String id : this.child.emote.nodeOrder()) {
                    Supplier<Matrix4fc> start = this.preparingFrame.nodeMatrices.get(id);
                    Matrix4fc fallback = new Matrix4f(this.emote.defaultMatrix(id));
                    this.transitionStartTransforms.put(id, start == null ? () -> fallback : start);
                }
                applyTransition(0);
                return;
            }
            if (segment.phase() == PlaybackTimeline.Phase.START_DELAY || segment.phase() == PlaybackTimeline.Phase.ANIMATION) {
                resetTarget();
                clearAppliedPose();
                this.child.clearAppliedPose();
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
            PreparedAnimation animation = (PreparedAnimation) this.child.emote;
            notifyLifecycle(listener -> listener.onStart(animation), false);
        }
    }

    private void closeChild(PlaybackStopReason reason) {
        if (this.child == null) return;
        try {
            this.child.stop(reason);
        } finally {
            Frame frame = this.preparingFrame;
            frame.notifications.add(new Notification(frame.view(), () -> frame.owner.eventAnimation = null, true));
            if (this.childCallbacksStarted) {
                this.childCallbacksStarted = false;
                notifyLifecycle(listener -> listener.onClose(reason), true);
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
                } else {
                    result = this.child.advance(delta, false);
                }
                if (!finishSequenceAnimation(segment, result)) return isFinished() ? AdvanceResult.FINISHED : AdvanceResult.CONTINUE;
            } else {
                int length = Objects.requireNonNull(sequencePhaseLength(this.sequenceSegment));
                int delta = Math.min(budget, Math.max(0, length - this.phaseTick));
                this.phaseTick += delta;
                this.currentTick += delta;
                this.lifetimeTicks += delta;
                budget -= delta;
                if (this.child != null) this.child.lifetimeTicks += delta;
                if (segment.phase() == PlaybackTimeline.Phase.TRANSITION) {
                    applyTransition((double) this.phaseTick / length);
                }
                if (this.phaseTick < length) break;
                this.segmentLengths.put(this.sequenceSegment, length);
            }
            this.sequenceSegment++;
            enterSequencePhase(true);
        }
        return isFinished() ? AdvanceResult.FINISHED : AdvanceResult.CONTINUE;
    }

    private boolean finishSequenceAnimation(PlaybackTimeline.Segment segment, AdvanceResult result) {
        copyChildPose();
        if (this.eventsStopped) return false;
        int animationTick = this.child.currentTick;
        notifyLifecycle(listener -> listener.onTick(animationTick), false);
        if (result != AdvanceResult.FINISHED && result != AdvanceResult.LOOP_BOUNDARY) return false;
        this.segmentLengths.put(this.sequenceSegment, this.phaseTick);
        var selected = selectedAnimation(segment);
        if (selected.loopDelayAfter() && this.child.model.settings().playback().mode() == EmoteAnimation.PlaybackMode.LOOP
            && this.sequenceSegment + 1 < this.sequencePhases.size()
            && this.sequencePhases.get(this.sequenceSegment + 1).phase() == PlaybackTimeline.Phase.LOOP_DELAY) {
            this.segmentLengths.put(this.sequenceSegment + 1, this.child.sampledLoopDelay);
        }
        closeChild(PlaybackStopReason.FINISHED);
        return !this.eventsStopped;
    }

    private void copyChildPose() {
        EmotePlayback frameChild = this.child;
        this.preparingFrame.applications.add(() -> {
            frameChild.appliedTransforms.forEach((id, matrix) -> this.appliedTransforms.put(id, new Matrix4f(matrix)));
            this.appliedVisibility.putAll(frameChild.appliedVisibility);
        });
    }

    private void hideUnusedNodes() {
        for (String id : this.emote.nodes().keySet()) if (!this.child.model.nodes().containsKey(id)) applyVisibility(id, false);
    }

    private void applyTransition(double progress) {
        preparePose(this.child.evaluator, this.transitionStartTransforms, (float) progress, 0, true, true);
        hideUnusedNodes();
    }

    private void applyTransitionPose(PoseStep step) {
        for (int index = 0; index < step.evaluator.nodeCount(); index++) {
            String id = step.evaluator.nodeId(index);
            if (!step.pose.visibility[index]) applyVisibility(id, false);
            if (step.pose.attachmentVisibility.get(index).containsValue(false)) {
                step.pose.attachmentVisibility.get(index).forEach((attachment, visible) -> {
                    if (!visible) this.target.setVisible(id, attachment, false);
                });
            }
            applyTransform(id, step.pose.matrices[index], step.pose.transformations[index], 0);
            applyVisibility(id, step.pose.visibility[index]);
            step.pose.attachmentVisibility.get(index).forEach((attachment, visible) -> {
                if (visible) this.target.setVisible(id, attachment, true);
            });
        }
    }

    public int currentTick() { return this.notificationView == null ? this.currentTick : this.notificationView.tick; }

    public boolean canSeek() {
        return this.phase != PlaybackPhase.NOT_STARTED && !this.eventsStopped && this.emote.playbackMode() != EmoteAnimation.PlaybackMode.SERVER_SYNC;
    }

    public boolean canSetTick(int tick) {
        if (tick < 0) throw new IllegalArgumentException("Tick must be non-negative");
        if ((this.emote instanceof PreparedSequence)) locateSequence(tick);
        else if (tick > this.model.clip().durationTicks()) throw new IllegalArgumentException("Time is outside the animation");
        return canSeek();
    }

    private void setTick(int tick) {
        if (!canSetTick(tick)) return;
        if ((this.emote instanceof PreparedSequence)) {
            seekSequence(locateSequence(tick), tick, null);
            return;
        }
        resetTarget();
        clearState();
        this.currentTick = tick;
        this.phase = PlaybackPhase.RUNNING;
        this.holdTicks = 0;
        this.evaluator.prepareFrame(tick, this.loopCount, 0, this.lifetimeTicks);
        applyAnimationPose(0, false);
        activateAnimationEvents(false);
    }

    public boolean canSetAnimationTick(int animationTick) {
        EmotePlayback animation = (this.emote instanceof PreparedSequence) ? this.child : this;
        if (animationTick < 0 || (animation != null && animationTick > animation.model.clip().durationTicks())) throw new IllegalArgumentException("Tick is outside the animation");
        return canSeek() && position().phase() == PlaybackTimeline.Phase.ANIMATION;
    }

    private void setAnimationTick(int animationTick) {
        if (!canSetAnimationTick(animationTick)) return;
        if (!(this.emote instanceof PreparedSequence)) { setTick(animationTick); return; }
        this.child.setTick(animationTick);
        if (this.child.model.clip().clock() == null) this.segmentLengths.put(this.sequenceSegment, this.phaseTick + this.child.model.clip().durationTicks() - animationTick);
        else this.segmentLengths.remove(this.sequenceSegment);
        this.segmentStarts.keySet().removeIf(index -> index > this.sequenceSegment);
        this.segmentLengths.keySet().removeIf(index -> index > this.sequenceSegment);
        clearAppliedPose();
        copyChildPose();
        hideUnusedNodes();
        notifyLifecycle(listener -> listener.onPositionChanged(animationTick), false);
    }

    public int animationDuration(PlaybackPosition position) {
        return this.emote instanceof PreparedSequence
            ? selectedAnimation(this.sequencePhases.get(position.segmentIndex())).animation().model().clip().durationTicks()
            : this.model.clip().durationTicks();
    }

    public EmoteAnimation.PlaybackMode playbackMode() { return this.emote.playbackMode(); }
    public @Nullable PoseView notificationPose() { return this.notificationView; }

    public int stepSegment(int stepIndex, int repeatIndex, int animationTick) {
        if (stepIndex < 0 || repeatIndex < 0 || animationTick < 0) throw new IllegalArgumentException("Step, repeat and tick must not be negative");
        for (var segment : this.sequencePhases) {
            if (segment.phase() != PlaybackTimeline.Phase.ANIMATION || !Objects.equals(segment.stepIndex(), stepIndex) || !Objects.equals(segment.repeatIndex(), repeatIndex)) continue;
            if (animationTick > selectedAnimation(segment).animation().model().clip().durationTicks()) throw new IllegalArgumentException("Time is outside the animation");
            return segment.segmentIndex();
        }
        throw new IllegalArgumentException("No animation at the specified sequence step and repeat");
    }

    private void setStep(int stepIndex, int repeatIndex, int animationTick) {
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
                resetTarget();
                clearAppliedPose();
                this.child.clearState();
                this.child.currentTick = source.animation().model().clip().durationTicks();
                this.child.evaluator.prepareFrame(this.child.currentTick, 0, 0, this.child.lifetimeTicks);
                this.child.applyAnimationPose(0, false);
                copyChildPose();
                hideUnusedNodes();
            } else {
                this.child = null;
                clearAppliedPose();
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
            clearAppliedPose();
            copyChildPose();
            hideUnusedNodes();
            notifyLifecycle(listener -> listener.onPositionChanged(local), false);
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
        if (this.notificationView != null) return this.notificationView.position;
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

    private void stop(PlaybackStopReason reason) {
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
        if (this.notificationView != null) return new Transformation(this.notificationView.nodeMatrix(nodeId));
        if (this.appliedPose != null) return new Transformation(this.appliedPose.nodeMatrix(nodeId));
        if ((this.emote instanceof PreparedSequence) && this.child != null && position().phase() == PlaybackTimeline.Phase.ANIMATION && this.child.model.nodes().containsKey(nodeId)) return this.child.currentTransformation(nodeId);
        Matrix4fc matrix = this.appliedTransforms.get(nodeId);
        if (matrix == null) return this.target.createTransformation(nodeId, this.emote.defaultMatrix(nodeId));
        return this.target.createTransformation(nodeId, matrix);
    }

    private void clearState() {
        this.transitionStartTransforms = new HashMap<>();
        clearAppliedPose();
        this.currentTick = 0;
        this.remainingDelay = 0;
        this.loopOverflow = 0;
        this.pendingUpdate = false;
    }

    private void resetTarget() {
        Frame frame = Objects.requireNonNull(this.preparingFrame);
        frame.applications.add(this.target::resetAll);
        frame.nodeMatrices.clear();
        for (String id : frame.owner.emote.nodeOrder()) {
            Matrix4fc matrix = new Matrix4f(frame.owner.emote.defaultMatrix(id));
            frame.nodeMatrices.put(id, () -> matrix);
        }
    }

    private void clearAppliedPose() {
        this.preparingFrame.applications.add(() -> {
            this.appliedTransforms.clear();
            this.appliedVisibility.clear();
            this.appliedAttachmentVisibility.clear();
            this.appliedNbt.clear();
            this.appliedNbtRemoved.clear();
        });
    }

    private void notifyLifecycle(Consumer<LifecycleListener> notification, boolean closing) {
        Frame frame = Objects.requireNonNull(this.preparingFrame);
        LifecycleListener listener = this.lifecycleListener;
        frame.notifications.add(new Notification(frame.view(), () -> notification.accept(listener), closing));
    }

    private void recordEventPose() {
        Frame frame = this.preparingFrame;
        PoseView pose = frame.view();
        frame.notifications.add(new Notification(pose, () -> {
            frame.owner.eventAnimation = this.model;
            frame.owner.eventPose = pose;
        }, false));
    }

    public final class Frame {
        private final EmotePlayback owner = EmotePlayback.this;
        private final Matrix4fc root = new Matrix4f(target.frameRoot().rotationMatrix());
        private final RootTransform placement;
        private final float orientationYaw = target.frameOrientationYaw();
        private final List<PoseStep> poses = new ArrayList<>();
        private final List<Runnable> applications = new ArrayList<>();
        private final List<Notification> notifications = new ArrayList<>();
        private final Set<EmotePlayback> playbacks = new HashSet<>();
        private final Map<String, Supplier<Matrix4fc>> nodeMatrices = new HashMap<>();
        private volatile FrameState state = FrameState.PREPARED;
        private AdvanceResult result;
        private PoseView finalPose;
        private @Nullable PoseView fixedView;
        private PlaybackTimeline timeline;
        private boolean eventsCancelled;

        private Frame() {
            RootTransform placement = target.frameRoot();
            this.placement = new RootTransform(placement.position(), placement.yaw(), placement.rotationMatrix());
            for (String id : emote.nodeOrder()) {
                Matrix4fc applied = appliedTransforms.get(id);
                Matrix4fc matrix = new Matrix4f(applied == null ? emote.defaultMatrix(id) : applied);
                this.nodeMatrices.put(id, () -> matrix);
            }
        }

        private PoseView view() { return this.fixedView == null ? new PoseView(owner.currentTick, owner.position(), root, placement, orientationYaw, nodeMatrices) : this.fixedView; }
        public AdvanceResult result() { return this.result; }
        public PoseView pose() { return this.finalPose; }
        public PlaybackTimeline timeline() { return this.timeline; }
        public void cancelEvents() { this.eventsCancelled = true; }

        public void calculate() {
            if (this.state != FrameState.PREPARED) throw new IllegalStateException("Frame is not ready for calculation");
            this.state = FrameState.CALCULATING;
            try {
                for (PoseStep step : this.poses) {
                    Map<String, Matrix4f> starts = null;
                    if (step.transitionStart != null) {
                        starts = new HashMap<>();
                        for (var entry : step.transitionStart.entrySet()) starts.put(entry.getKey(), new Matrix4f(entry.getValue().get()));
                    }
                    step.evaluator.calculatePose(step.pose, this.root, starts, step.progress);
                }
                this.state = FrameState.CALCULATED;
            } catch (RuntimeException | Error exception) {
                this.state = FrameState.FAILED;
                throw exception;
            }
        }

        public void apply() {
            if (this.state != FrameState.CALCULATED && !(this.state == FrameState.PREPARED && this.poses.isEmpty())) throw new IllegalStateException("Frame has not been calculated");
            try {
                for (Runnable application : this.applications) application.run();
                owner.appliedPose = this.finalPose;
                this.state = FrameState.APPLIED;
                if (owner.pendingFrame == this) owner.pendingFrame = null;
            } catch (RuntimeException | Error exception) {
                this.state = FrameState.FAILED;
                throw exception;
            }
        }

        public void dispatchEvents() {
            if (this.state != FrameState.APPLIED) throw new IllegalStateException("Frame has not been applied");
            this.state = FrameState.DISPATCHED;
            PoseView previous = owner.notificationView;
            try {
                for (Notification notification : this.notifications) {
                    if (this.eventsCancelled) break;
                    if (owner.eventsStopped && !notification.closing) continue;
                    owner.notificationView = notification.view;
                    notification.action.run();
                }
                if (!this.eventsCancelled && owner.eventAnimation != null) owner.eventPose = this.finalPose;
            } finally {
                owner.notificationView = previous;
            }
        }

        public void discard() {
            if (this.state == FrameState.CALCULATING) throw new IllegalStateException("Calculation must finish before discarding its inputs");
            if (this.state == FrameState.APPLIED || this.state == FrameState.DISPATCHED) throw new IllegalStateException("Applied frame cannot be discarded");
            this.state = FrameState.DISCARDED;
            if (owner.pendingFrame == this) owner.pendingFrame = null;
        }
    }

    public static final class PoseView {
        private final int tick;
        private final PlaybackPosition position;
        private final Matrix4fc root;
        private final RootTransform placement;
        private final float orientationYaw;
        private final Map<String, Supplier<Matrix4fc>> nodeMatrices;

        private PoseView(int tick, PlaybackPosition position, Matrix4fc root, RootTransform placement, float orientationYaw,
                         Map<String, Supplier<Matrix4fc>> nodeMatrices) {
            this.tick = tick;
            this.position = position;
            this.root = root;
            this.placement = placement;
            this.orientationYaw = orientationYaw;
            this.nodeMatrices = Map.copyOf(nodeMatrices);
        }

        public Matrix4fc nodeMatrix(String id) {
            Supplier<Matrix4fc> matrix = this.nodeMatrices.get(id);
            if (matrix == null) throw new IllegalArgumentException("Unknown node: " + id);
            return new Matrix4f(this.root).mul(matrix.get());
        }

        public RootTransform root() { return this.placement; }
        public float orientationYaw() { return this.orientationYaw; }
        public int tick() { return this.tick; }
        public PlaybackPosition position() { return this.position; }
    }

    private record PoseStep(AnimationEvaluator evaluator, AnimationEvaluator.Pose pose,
                            @Nullable Map<String, Supplier<Matrix4fc>> transitionStart, float progress) {
        private PoseStep {
            if (transitionStart != null) transitionStart = Map.copyOf(transitionStart);
        }
    }

    private record Notification(PoseView view, Runnable action, boolean closing) {}
    private enum FrameState { PREPARED, CALCULATING, CALCULATED, APPLIED, DISPATCHED, DISCARDED, FAILED }

    private void applyEvaluator(PoseStep step, int interpolationDurationTicks) {
        for (int index = 0; index < step.evaluator.nodeCount(); index++) {
            String nodeId = step.evaluator.nodeId(index);
            Map<String, Boolean> previous = this.appliedAttachmentVisibility.computeIfAbsent(nodeId, ignored -> new HashMap<>());
            if (!step.pose.visibility[index]) applyVisibility(nodeId, false);
            if (step.pose.attachmentVisibility.get(index).containsValue(false)) {
                step.pose.attachmentVisibility.get(index).forEach((attachmentId, visible) -> {
                    if (!visible && !Boolean.FALSE.equals(previous.put(attachmentId, false))) this.target.setVisible(nodeId, attachmentId, false);
                });
            }
            applyTransform(nodeId, step.pose.matrices[index], step.pose.transformations[index], interpolationDurationTicks);
            applyVisibility(nodeId, step.pose.visibility[index]);
            step.pose.attachmentVisibility.get(index).forEach((attachmentId, visible) -> {
                if (!Boolean.valueOf(visible).equals(previous.put(attachmentId, visible))) {
                    this.target.setVisible(nodeId, attachmentId, visible);
                }
            });
            for (var attachment : step.pose.nbt.getOrDefault(nodeId, Map.of()).entrySet()) {
                applyNbt(nodeId, attachment.getKey(), attachment.getValue(), step.pose.nbtRemoved.get(nodeId).get(attachment.getKey()));
            }
        }
    }

    private void applyTransform(
        String nodeId,
        Matrix4fc matrix,
        Transformation transformation,
        int interpolationDurationTicks
    ) {
        Matrix4f applied = this.appliedTransforms.get(nodeId);
        if (applied != null && matrix.equals(applied)) return;
        if (applied == null) this.appliedTransforms.put(nodeId, new Matrix4f(matrix));
        else applied.set(matrix);
        this.target.applyTransform(nodeId, transformation, interpolationDurationTicks);
    }

    private void applyVisibility(String nodeId, boolean visible) {
        if (this.preparingFrame != null) {
            this.preparingFrame.applications.add(() -> applyVisibility(nodeId, visible));
            return;
        }
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
        execute(events.stream().map(event -> new EmotePlayback.EventOccurrence(event, animationId, animationTick, phase, this.preparingFrame.view())).toList());
    }

    private void execute(List<EmotePlayback.EventOccurrence> events) {
        if (this.eventExecutor == null) {
            return;
        }
        for (EmotePlayback.EventOccurrence event : events) {
            if (this.eventsStopped && event.phase() != AnimationEventPhase.STOP) break;
            EventExecutor executor = this.eventExecutor;
            Frame frame = this.preparingFrame;
            frame.notifications.add(new Notification(event.pose(), () -> {
                if (event.phase() == AnimationEventPhase.STOP && frame.owner.eventAnimation != null && frame.owner.eventAnimation.id().equals(event.animationId())) frame.owner.eventAnimation = null;
                executor.execute(event);
            }, event.phase() == AnimationEventPhase.STOP));
        }
    }

    @FunctionalInterface
    public interface EventExecutor {
        void execute(EmotePlayback.EventOccurrence event);
    }

    public interface LifecycleListener {
        default void onStart(PreparedAnimation animation) {}
        default void onTick(int animationTick) {}
        default void onLoop() {}
        default void onClose(PlaybackStopReason reason) {}
        default void onPositionChanged(int animationTick) {}
    }

    public interface PoseTarget {
        Matrix4fc rootMatrix();
        default RootTransform root() { return new RootTransform(Vec3.ZERO, 0, new Matrix4f(rootMatrix())); }
        default float orientationYaw() { return root().yaw(); }
        default RootTransform frameRoot() { return root(); }
        default float frameOrientationYaw() { return orientationYaw(); }

        Transformation createTransformation(String nodeId, Matrix4fc matrix);

        void applyTransform(String nodeId, Transformation transformation, int interpolationDurationTicks);

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
            .map(event -> new EventOccurrence(event.event(), this.model.id(), event.tick(), AnimationEventPhase.TIMELINE, this.preparingFrame.view())).toList();
    }

    private List<EventOccurrence> timelineEventsAt(int tick) {
        return this.model.clip().events().timeline().stream()
            .filter(event -> event.tick() == tick && event.direction() != EmoteAnimation.Direction.BACKWARD)
            .map(event -> new EventOccurrence(event.event(), this.model.id(), event.tick(), AnimationEventPhase.TIMELINE, this.preparingFrame.view())).toList();
    }

    public record EventOccurrence(
        EmoteAnimation.Event event,
        Identifier animationId,
        int animationTick,
        AnimationEventPhase phase,
        PoseView pose
    ) {
        public EventOccurrence {
            Objects.requireNonNull(event, "event");
            Objects.requireNonNull(animationId, "animationId");
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(pose, "pose");
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
