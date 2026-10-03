package io.github.hanhy06.emote.playback;

import com.mojang.math.Transformation;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.api.PlaybackPosition;
import io.github.hanhy06.emote.api.PlaybackTimeline;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.content.AnimationEventPhase;
import io.github.hanhy06.emote.content.PreparedEmote;
import io.github.hanhy06.emote.playback.molang.MolangQuerySource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PlaybackPlayer {
    private final EmoteAnimation model;
    private final PreparedEmote emote;
    private final TimelineTarget target;
    private final MolangQuerySource querySource;
    private AnimationEvaluator evaluator;
    private final Map<String, Matrix4f> appliedTransforms = new HashMap<>();
    private final Map<String, Boolean> appliedVisibility = new HashMap<>();
    private final Map<String, CompoundTag> appliedNbt = new HashMap<>();

    private int currentTick;
    private int remainingLoopDelay;
    private int loopCount;
    private int activePlaybackSegment = -1;
    private PlaybackPhase phase = PlaybackPhase.NOT_STARTED;
    private boolean initialVisibilityDeferred;
    private EventExecutor eventExecutor;
    private boolean eventsStarted;
    private boolean eventsStopped;
    private LifecycleListener lifecycleListener = new LifecycleListener() {};
    private int lifecycleSegment = -1;
    private boolean pendingLoopCallback;
    private long holdTicks;
    private PlaybackPosition stoppedPosition;
    private int resumedTransitionSegment = -1;
    private final Map<String, Matrix4f> transitionStartTransforms = new HashMap<>();

    public PlaybackPlayer(PreparedEmote emote, TimelineTarget target) {
        this(emote, target, MolangQuerySource.EMPTY);
    }

    public PlaybackPlayer(PreparedEmote emote, TimelineTarget target, MolangQuerySource querySource) {
        this.emote = Objects.requireNonNull(emote, "emote");
        this.model = emote.model();
        this.target = Objects.requireNonNull(target, "target");
        this.querySource = Objects.requireNonNull(querySource, "querySource");
        this.evaluator = emote.playbackSegments().isEmpty()
            ? new AnimationEvaluator(emote, this.querySource)
            : null;
    }

    public void start() {
        if (this.phase != PlaybackPhase.NOT_STARTED) {
            throw new IllegalStateException("Timeline already started");
        }
        this.phase = PlaybackPhase.RUNNING;
        resetToTick(0);
    }

    public void startSynchronized(long serverTick) {
        if (this.phase != PlaybackPhase.NOT_STARTED) {
            throw new IllegalStateException("Timeline already started");
        }
        if (this.model.settings().playback().mode() != EmoteAnimation.LoopMode.SERVER_SYNC) {
            throw new IllegalStateException("Timeline is not server synchronized");
        }

        startAtCyclePhaseUnchecked(serverTick);
    }

    public void startAtCyclePhase(long cycleTick) {
        if (this.phase != PlaybackPhase.NOT_STARTED) {
            throw new IllegalStateException("Timeline already started");
        }
        startAtCyclePhaseUnchecked(cycleTick);
    }

    private void startAtCyclePhaseUnchecked(long cycleTick) {
        this.phase = PlaybackPhase.RUNNING;
        clearState();
        int duration = this.model.timeline().durationTicks();
        EmoteAnimation.PlaybackSettings playback = this.model.settings().playback();
        int cycleStart = playback.mode() == EmoteAnimation.LoopMode.LOOP ? playback.loopStartTicks() : 0;
        int cycleEnd = duration;
        long cycleLength = (long) cycleEnd - cycleStart + playback.loopDelayTicks();
        long phase = Math.floorMod(cycleTick, cycleLength);
        int timelineTick = cycleStart + (int) Math.min(phase, cycleEnd - cycleStart);
        this.currentTick = timelineTick;
        this.loopCount = (int) Math.min(Integer.MAX_VALUE, Math.floorDiv(cycleTick, cycleLength));
        applySynchronizedSnapshot(timelineTick);
        if (phase >= cycleEnd - cycleStart) {
            this.remainingLoopDelay = (int) (cycleLength - phase);
            this.phase = PlaybackPhase.LOOP_DELAY;
        }
    }

    public void deferInitialVisibility() {
        if (this.phase == PlaybackPhase.NOT_STARTED) {
            throw new IllegalStateException("Timeline has not started");
        }
        this.model.nodes().keySet().forEach(nodeId -> this.target.setVisible(nodeId, false));
        this.initialVisibilityDeferred = true;
    }

    public void restoreDeferredVisibility() {
        if (!this.initialVisibilityDeferred) {
            return;
        }
        this.initialVisibilityDeferred = false;
        this.model.nodes().forEach((nodeId, node) -> this.target.setVisible(
            nodeId,
            this.appliedVisibility.getOrDefault(nodeId, node.visible())
        ));
    }

    public void bindEvents(EventExecutor eventExecutor) {
        if (this.eventExecutor != null) {
            throw new IllegalStateException("Animation events are already bound");
        }
        this.eventExecutor = Objects.requireNonNull(eventExecutor, "eventExecutor");
    }

    public void bindLifecycleListener(LifecycleListener listener) {
        this.lifecycleListener = Objects.requireNonNull(listener, "listener");
    }

    public void startEvents() {
        if (this.eventsStarted) {
            throw new IllegalStateException("Events already started");
        }
        this.eventsStarted = true;
        if (!this.emote.playbackSegments().isEmpty()) {
            this.lifecycleSegment = -1;
            startSegmentEvents();
            return;
        }
        execute(
            this.model.timeline().events().start(),
            this.model.id(),
            this.currentTick,
            AnimationEventPhase.START
        );
        if (this.currentTick == 0) {
            execute(this.emote.timelineEvents(0));
        }
    }

    public AdvanceResult advance() {
        return advance(true);
    }

    public AdvanceResult advance(boolean continueAfterLoopBoundary) {
        if (!this.emote.playbackSegments().isEmpty()) return advanceSequence();
        int previousTick = this.currentTick;
        AdvanceResult result = advanceTimeline();
        if (result != AdvanceResult.RESTARTED && this.currentTick != previousTick) {
            execute(this.emote.timelineEvents(this.currentTick));
        }
        if (result == AdvanceResult.LOOP_BOUNDARY && this.eventsStarted) {
            execute(
                this.model.timeline().events().loop(),
                this.model.id(),
                this.model.timeline().durationTicks(),
                AnimationEventPhase.LOOP
            );
            if (this.eventsStopped) return AdvanceResult.FINISHED;
            this.pendingLoopCallback = this.phase == PlaybackPhase.LOOP_BOUNDARY;
            if (continueAfterLoopBoundary && this.phase == PlaybackPhase.LOOP_BOUNDARY) {
                result = continueAfterLoopEvent();
            } else if (this.phase != PlaybackPhase.LOOP_BOUNDARY) {
                result = AdvanceResult.CONTINUE;
            }
        }
        if (result == AdvanceResult.RESTARTED) {
            execute(this.emote.timelineEvents(this.currentTick));
            if (this.pendingLoopCallback && !this.eventsStopped) {
                this.pendingLoopCallback = false;
                this.lifecycleListener.onLoop();
            }
        }
        return result;
    }

    private AdvanceResult advanceSequence() {
        if (this.phase == PlaybackPhase.NOT_STARTED) throw new IllegalStateException("Timeline has not started");
        if (this.phase == PlaybackPhase.FINISHED || this.eventsStopped) return AdvanceResult.FINISHED;
        this.currentTick++;
        boolean segmentFinished = false;
        if (this.lifecycleSegment >= 0) {
            PreparedEmote.PlaybackSegment segment = this.emote.playbackSegments().get(this.lifecycleSegment);
            int localTick = this.currentTick - segment.startTick();
            applySegment(this.lifecycleSegment, this.currentTick);
            if (this.currentTick < segment.endTick()) applyHiddenNodes(this.currentTick);
            execute(segment.animation().timelineEvents(localTick));
            if (this.eventsStopped) return AdvanceResult.FINISHED;
            this.lifecycleListener.onTick(localTick);
            if (this.eventsStopped) return AdvanceResult.FINISHED;
            if (this.lifecycleListener.hasPendingTick()) return AdvanceResult.CONTINUE;
            if (this.currentTick >= segment.endTick()) {
                EmoteAnimation source = segment.animation().model();
                if (source.settings().playback().mode() == EmoteAnimation.LoopMode.LOOP
                    || source.settings().playback().mode() == EmoteAnimation.LoopMode.SERVER_SYNC) {
                    execute(source.timeline().events().loop(), source.id(), localTick, AnimationEventPhase.LOOP);
                    if (this.eventsStopped) return AdvanceResult.FINISHED;
                }
                closeSegment(PlaybackStopReason.FINISHED);
                segmentFinished = true;
                if (this.eventsStopped) return AdvanceResult.FINISHED;
            }
        }
        if (this.lifecycleSegment < 0) {
            int nextSegment = this.activePlaybackSegment + 1;
            boolean nextPoseStarts = nextSegment < this.emote.playbackSegments().size()
                && this.emote.playbackSegments().get(nextSegment).transitionStartTick() == this.currentTick;
            if (!segmentFinished || nextPoseStarts) applyTick(this.currentTick);
            else applyHiddenNodes(this.currentTick);
            if (this.eventsStarted) startSegmentEvents();
        }
        if (this.currentTick >= this.model.timeline().durationTicks()) {
            this.phase = PlaybackPhase.FINISHED;
            return AdvanceResult.FINISHED;
        }
        return this.eventsStopped ? AdvanceResult.FINISHED : AdvanceResult.CONTINUE;
    }

    private void startSegmentEvents() {
        if (this.lifecycleSegment >= 0 || this.eventsStopped) return;
        int index = locateSequence(this.currentTick).animationSegment();
        if (index < 0) return;
        PreparedEmote.PlaybackSegment segment = this.emote.playbackSegments().get(index);
        this.lifecycleSegment = index;
        EmoteAnimation source = segment.animation().model();
        int localTick = this.currentTick - segment.startTick();
        execute(source.timeline().events().start(), source.id(), localTick, AnimationEventPhase.START);
        if (this.eventsStopped) return;
        execute(segment.animation().timelineEvents(localTick));
        if (!this.eventsStopped) this.lifecycleListener.onStart(segment.animation());
    }

    private void closeSegment(PlaybackStopReason reason) {
        if (this.lifecycleSegment < 0) return;
        PreparedEmote.PlaybackSegment segment = this.emote.playbackSegments().get(this.lifecycleSegment);
        this.lifecycleSegment = -1;
        EmoteAnimation source = segment.animation().model();
        try {
            execute(source.timeline().events().stop(), source.id(), this.currentTick - segment.startTick(), AnimationEventPhase.STOP);
        } finally {
            this.lifecycleListener.onClose(reason);
        }
    }

    private AdvanceResult advanceTimeline() {
        if (this.phase == PlaybackPhase.NOT_STARTED) {
            throw new IllegalStateException("Timeline has not started");
        }
        if (this.phase == PlaybackPhase.FINISHED) {
            return AdvanceResult.FINISHED;
        }
        if (this.phase == PlaybackPhase.LOOP_BOUNDARY) {
            throw new IllegalStateException("Loop boundary must be continued before advancing");
        }

        if (this.phase == PlaybackPhase.LOOP_DELAY) {
            this.remainingLoopDelay--;
            if (this.remainingLoopDelay == 0) {
                this.loopCount++;
                this.phase = PlaybackPhase.RUNNING;
                resetToLoopStart();
                return AdvanceResult.RESTARTED;
            }
            return AdvanceResult.CONTINUE;
        }

        if (this.phase == PlaybackPhase.HOLDING) {
            this.holdTicks++;
            return AdvanceResult.CONTINUE;
        }

        this.currentTick++;
        applyTick(this.currentTick);
        if (this.model.settings().playback().mode() == EmoteAnimation.LoopMode.LOOP
            && this.currentTick >= this.model.timeline().durationTicks()) {
            this.phase = PlaybackPhase.LOOP_BOUNDARY;
            return AdvanceResult.LOOP_BOUNDARY;
        }
        if (this.currentTick < this.model.timeline().durationTicks()) {
            return AdvanceResult.CONTINUE;
        }
        if (this.model.settings().playback().mode() == EmoteAnimation.LoopMode.ONCE) {
            this.phase = PlaybackPhase.FINISHED;
            return AdvanceResult.FINISHED;
        }
        if (this.model.settings().playback().mode() == EmoteAnimation.LoopMode.HOLD) {
            this.phase = PlaybackPhase.HOLDING;
            return AdvanceResult.CONTINUE;
        }
        this.phase = PlaybackPhase.LOOP_BOUNDARY;
        return AdvanceResult.LOOP_BOUNDARY;
    }

    public AdvanceResult continueAfterLoopEvent() {
        if (this.phase != PlaybackPhase.LOOP_BOUNDARY) {
            throw new IllegalStateException("Timeline is not at a loop boundary");
        }
        int loopDelay = this.model.settings().playback().loopDelayTicks();
        if (loopDelay == 0) {
            this.loopCount++;
            this.phase = PlaybackPhase.RUNNING;
            resetToLoopStart();
            return AdvanceResult.RESTARTED;
        }
        this.remainingLoopDelay = loopDelay;
        this.phase = PlaybackPhase.LOOP_DELAY;
        return AdvanceResult.CONTINUE;
    }

    public int currentTick() {
        return this.currentTick;
    }

    public boolean canSetTick(int tick) {
        if (tick < 0 || tick >= this.model.timeline().durationTicks()) throw new IllegalArgumentException("Tick is outside the timeline");
        return this.phase != PlaybackPhase.NOT_STARTED && !this.eventsStopped
            && this.model.settings().playback().mode() != EmoteAnimation.LoopMode.SERVER_SYNC;
    }

    public int animationTickTarget(int tick) {
        if (tick < 0) throw new IllegalArgumentException("Tick must not be negative");
        PlaybackPosition position = position();
        if (position.phase() != PlaybackTimeline.Phase.ANIMATION) return -1;
        PlaybackTimeline.Segment segment = timeline().segments().get(position.segmentIndex());
        if (tick >= segment.endTick() - segment.startTick()) throw new IllegalArgumentException("Tick is outside the animation");
        return Math.toIntExact(segment.startTick() + tick);
    }

    public int stepTickTarget(int stepIndex, int repeatIndex, int tick) {
        if (stepIndex < 0 || repeatIndex < 0 || tick < 0) throw new IllegalArgumentException("Step, repeat and tick must not be negative");
        for (PlaybackTimeline.Segment segment : timeline().segments()) {
            if (segment.phase() != PlaybackTimeline.Phase.ANIMATION
                || !Objects.equals(segment.stepIndex(), stepIndex) || !Objects.equals(segment.repeatIndex(), repeatIndex)) continue;
            if (tick >= segment.endTick() - segment.startTick()) throw new IllegalArgumentException("Tick is outside the animation");
            return Math.toIntExact(segment.startTick() + tick);
        }
        throw new IllegalArgumentException("No animation at the specified sequence step and repeat");
    }

    public boolean isFinished() {
        return this.phase == PlaybackPhase.FINISHED || this.eventsStopped;
    }

    public void setTick(int tick) {
        if (!canSetTick(tick) || (tick == this.currentTick && this.phase == PlaybackPhase.RUNNING)) return;
        if (this.emote.playbackSegments().isEmpty()) {
            this.target.resetAll();
            clearState();
            this.currentTick = tick;
            this.phase = PlaybackPhase.RUNNING;
            this.holdTicks = 0;
            this.pendingLoopCallback = false;
            this.evaluator.setTick(tick, this.loopCount);
            applyEvaluator(0);
            if (this.eventsStarted) execute(this.emote.timelineEvents(tick));
            return;
        }
        SequenceLocation location = locateSequence(tick);
        int destination = location.animationSegment();
        boolean sameExecution = destination >= 0 && destination == this.lifecycleSegment;
        AnimationEvaluator previousEvaluator = sameExecution ? this.evaluator : null;
        if (!sameExecution) closeSegment(PlaybackStopReason.REPLACED);
        if (this.eventsStopped) return;
        clearState();
        this.target.resetAll();
        this.currentTick = tick;
        this.phase = PlaybackPhase.RUNNING;
        if (destination >= 0) {
            var segment = this.emote.playbackSegments().get(destination);
            int localTick = tick - segment.startTick();
            this.activePlaybackSegment = destination;
            this.evaluator = previousEvaluator != null ? previousEvaluator : new AnimationEvaluator(segment.animation(), this.querySource);
            if (previousEvaluator != null) this.evaluator.setTick(localTick, 0);
            else this.evaluator.beginCycle(localTick, 0);
            applyEvaluator(0);
            for (String nodeId : this.model.nodes().keySet()) {
                if (!segment.animation().model().nodes().containsKey(nodeId)) applyVisibility(nodeId, false);
            }
            this.lifecycleSegment = destination;
            this.lifecycleListener.onPositionChanged(localTick);
            if (this.eventsStarted) {
                if (sameExecution) {
                    execute(segment.animation().timelineEvents(localTick));
                } else {
                    this.lifecycleSegment = -1;
                    startSegmentEvents();
                }
            }
        } else {
            restoreSequenceGap(tick, location);
        }
    }

    private void restoreSequenceGap(int tick, SequenceLocation location) {
        List<PreparedEmote.PlaybackSegment> segments = this.emote.playbackSegments();
        int previous = location.previousSegment();
        int next = location.transitionSegment();
        this.activePlaybackSegment = previous;
        if (previous >= 0) {
            var segment = segments.get(previous);
            this.evaluator = new AnimationEvaluator(segment.animation(), this.querySource);
            this.evaluator.beginCycle(segment.endTick() - segment.startTick(), 0);
            applyEvaluator(0);
        }
        for (String nodeId : this.model.nodes().keySet()) {
            if (previous < 0 || !segments.get(previous).animation().model().nodes().containsKey(nodeId)) applyVisibility(nodeId, false);
        }
        if (next >= 0) {
            var segment = segments.get(next);
            this.activePlaybackSegment = next;
            this.evaluator = new AnimationEvaluator(segment.animation(), this.querySource);
            this.evaluator.beginCycle(0, 0);
            this.resumedTransitionSegment = next;
            for (int index = 0; index < this.evaluator.nodeCount(); index++) {
                String nodeId = this.evaluator.nodeId(index);
                Matrix4fc previousMatrix = this.appliedTransforms.getOrDefault(nodeId, this.emote.defaultTransform(nodeId).localMatrix());
                this.transitionStartTransforms.put(nodeId, new Matrix4f(previousMatrix));
            }
            applyTransitionTick(next, tick);
        }
        applyHiddenNodes(tick);
    }

    public PlaybackTimeline timeline() {
        return this.emote.playbackTimeline();
    }

    public PlaybackPosition position() {
        if (this.stoppedPosition != null) return this.stoppedPosition;
        List<PlaybackTimeline.Segment> segments = timeline().segments();
        PlaybackTimeline.Segment selected = segments.getLast();
        long phaseTick;
        if (this.emote.playbackSegments().isEmpty() && segments.getFirst().phase() == PlaybackTimeline.Phase.ANIMATION) {
            if (this.phase == PlaybackPhase.LOOP_DELAY) {
                selected = segments.getLast();
                phaseTick = this.model.settings().playback().loopDelayTicks() - this.remainingLoopDelay;
            } else if (this.phase == PlaybackPhase.HOLDING) {
                selected = segments.getLast();
                phaseTick = this.holdTicks;
            } else {
                selected = segments.getFirst();
                phaseTick = this.currentTick;
            }
        } else {
            selected = this.lifecycleSegment < 0 ? locateSequence(this.currentTick).timelineSegment()
                : segments.get(this.emote.playbackSegments().get(this.lifecycleSegment).timelineSegmentIndex());
            phaseTick = this.currentTick - selected.startTick();
        }
        return new PlaybackPosition(selected.segmentIndex(), selected.stepIndex(), selected.repeatIndex(),
            selected.phase(), phaseTick, selected.animationId(),
            selected.phase() == PlaybackTimeline.Phase.ANIMATION ? (int) phaseTick : null);
    }

    public Identifier emoteId() {
        return this.model.id();
    }

    public float rotationDeadzone() {
        if (this.activePlaybackSegment < 0) {
            return this.model.settings().rotationDeadzone();
        }
        return this.emote.playbackSegments().get(this.activePlaybackSegment)
            .animation().model().settings().rotationDeadzone();
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
        if (this.emote.playbackSegments().isEmpty()) {
            execute(
                this.model.timeline().events().stop(),
                this.model.id(),
                this.currentTick,
                AnimationEventPhase.STOP
            );
            return;
        }
        closeSegment(reason);
    }

    public Transformation currentTransformation(String nodeId) {
        Matrix4fc matrix = this.appliedTransforms.get(nodeId);
        if (matrix == null) return this.target.createTransformation(nodeId, this.emote.defaultTransform(nodeId));
        return this.target.createTransformation(nodeId, matrix, this.evaluator.preservesMatrix(nodeId));
    }

    private void resetToLoopStart() {
        int tick = this.model.settings().playback().loopStartTicks();
        if (!this.emote.playbackSegments().isEmpty()) {
            resetToTick(tick);
            return;
        }
        this.currentTick = tick;
        this.evaluator.rewindLoop(tick, this.loopCount);
        applyEvaluator(this.evaluator.displayInterpolationTicks());
    }

    private void resetToTick(int tick) {
        this.target.resetAll();
        clearState();
        this.currentTick = tick;
        if (this.evaluator == null) {
            applyTick(tick);
        } else {
            this.evaluator.beginCycle(tick, this.loopCount);
            applyEvaluator(0);
        }
    }

    private void clearState() {
        this.resumedTransitionSegment = -1;
        this.transitionStartTransforms.clear();
        this.appliedTransforms.clear();
        this.appliedVisibility.clear();
        this.appliedNbt.clear();
        this.activePlaybackSegment = -1;
        if (!this.emote.playbackSegments().isEmpty()) {
            this.evaluator = null;
        }
        this.currentTick = 0;
        this.remainingLoopDelay = 0;
    }

    private void applySynchronizedSnapshot(int tick) {
        this.evaluator.beginCycle(tick, this.loopCount);
        applyEvaluator(0);
    }

    private void applyTick(int tick) {
        if (!this.emote.playbackSegments().isEmpty()) {
            applyPlaybackSegment(tick);
            applyHiddenNodes(tick);
            return;
        }
        this.evaluator.evaluate(tick, this.loopCount);
        applyEvaluator(tick == 0 ? 0 : this.evaluator.displayInterpolationTicks());
    }

    private void applyPlaybackSegment(int tick) {
        int selected = locateSequence(tick).poseSegment();
        if (selected >= 0) applySegment(selected, tick);
    }

    private SequenceLocation locateSequence(int tick) {
        PlaybackTimeline.Segment selected = timeline().segments().getLast();
        for (PlaybackTimeline.Segment segment : timeline().segments()) {
            if (tick >= segment.startTick() && (segment.endTick() == null || tick < segment.endTick())) {
                selected = segment;
                break;
            }
        }
        int animation = -1;
        int previous = -1;
        int transition = -1;
        int pose = -1;
        List<PreparedEmote.PlaybackSegment> segments = this.emote.playbackSegments();
        for (int index = 0; index < segments.size(); index++) {
            PreparedEmote.PlaybackSegment segment = segments.get(index);
            if (segment.endTick() <= tick) previous = index;
            if (tick >= segment.startTick() && tick < segment.endTick()) animation = index;
            if (tick >= segment.transitionStartTick() && tick < segment.startTick()) transition = index;
            if (tick >= segment.transitionStartTick() && tick <= segment.endTick()) pose = index;
        }
        return new SequenceLocation(selected, animation, previous, transition, pose);
    }

    private record SequenceLocation(PlaybackTimeline.Segment timelineSegment, int animationSegment,
                                    int previousSegment, int transitionSegment, int poseSegment) {}

    private void applySegment(int selected, int tick) {
        PreparedEmote.PlaybackSegment segment = this.emote.playbackSegments().get(selected);
        int localTick = tick - segment.startTick();
        boolean segmentChanged = selected != this.activePlaybackSegment;
        if (segmentChanged) {
            this.activePlaybackSegment = selected;
            this.evaluator = new AnimationEvaluator(segment.animation(), this.querySource);
            this.evaluator.beginCycle(Math.max(localTick, 0), 0);
        } else if (localTick > 0) {
            this.evaluator.evaluate(localTick, 0);
        }
        if (localTick < 0) {
            if (this.resumedTransitionSegment == selected) {
                applyTransitionTick(selected, tick);
                return;
            }
            if (segmentChanged) {
                applyEvaluatorTransforms(segment.startTick() - segment.transitionStartTick());
            }
            return;
        }
        if (localTick == 0) {
            this.resumedTransitionSegment = -1;
            this.transitionStartTransforms.clear();
            this.appliedNbt.keySet().forEach(this.target::resetNbt);
            this.appliedNbt.clear();
        }
        applyEvaluator(tick == 0 || localTick == 0 ? 0 : this.evaluator.displayInterpolationTicks());
    }

    private void applyTransitionTick(int selected, int tick) {
        var segment = this.emote.playbackSegments().get(selected);
        float progress = (float) (tick - segment.transitionStartTick()) / (segment.startTick() - segment.transitionStartTick());
        for (int index = 0; index < this.evaluator.nodeCount(); index++) {
            String nodeId = this.evaluator.nodeId(index);
            Transformation start = new Transformation(new Matrix4f(this.transitionStartTransforms.get(nodeId)));
            Transformation end = new Transformation(new Matrix4f(this.evaluator.matrix(index)));
            applyTransform(nodeId, start.slerp(end, progress).getMatrix(), this.evaluator.preservesMatrix(index), 0);
        }
    }

    private void applyHiddenNodes(int tick) {
        this.emote.hiddenNodes(tick).forEach(nodeId -> applyVisibility(nodeId, false));
    }

    private void applyEvaluator(int interpolationDurationTicks) {
        for (int index = 0; index < this.evaluator.nodeCount(); index++) {
            String nodeId = applyEvaluatorTransform(index, interpolationDurationTicks);
            applyVisibility(nodeId, this.evaluator.visible(index));
            CompoundTag nbt = this.evaluator.nbt(index);
            if (nbt != null) applyNbt(nodeId, nbt);
        }
    }

    private void applyEvaluatorTransforms(int interpolationDurationTicks) {
        for (int index = 0; index < this.evaluator.nodeCount(); index++) {
            applyEvaluatorTransform(index, interpolationDurationTicks);
        }
    }

    private String applyEvaluatorTransform(int index, int interpolationDurationTicks) {
        String nodeId = this.evaluator.nodeId(index);
        applyTransform(
            nodeId,
            this.evaluator.matrix(index),
            this.evaluator.preservesMatrix(index),
            interpolationDurationTicks
        );
        return nodeId;
    }

    private void applyTransform(
        String nodeId,
        Matrix4fc matrix,
        boolean preserveMatrix,
        int interpolationDurationTicks
    ) {
        Matrix4f applied = this.appliedTransforms.get(nodeId);
        if (applied != null && matrix.equals(applied)) return;
        if (applied == null) this.appliedTransforms.put(nodeId, new Matrix4f(matrix));
        else applied.set(matrix);
        this.target.applyTransform(nodeId, matrix, preserveMatrix, interpolationDurationTicks);
    }

    private void applyVisibility(String nodeId, boolean visible) {
        if (Boolean.valueOf(visible).equals(this.appliedVisibility.put(nodeId, visible))) {
            return;
        }
        this.target.setVisible(nodeId, visible);
    }

    private void applyNbt(String nodeId, CompoundTag nbt) {
        CompoundTag previous = this.appliedNbt.get(nodeId);
        if (nbt.equals(previous)) return;
        this.appliedNbt.put(nodeId, nbt.copy());
        this.target.applyNbt(nodeId, nbt);
    }

    public enum AdvanceResult {
        CONTINUE,
        LOOP_BOUNDARY,
        RESTARTED,
        FINISHED
    }

    private enum PlaybackPhase {
        NOT_STARTED,
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
        execute(events.stream().map(event -> new PreparedEmote.PreparedEvent(event, animationId, animationTick, phase)).toList());
    }

    private void execute(List<PreparedEmote.PreparedEvent> events) {
        if (this.eventExecutor == null) {
            return;
        }
        for (PreparedEmote.PreparedEvent event : events) {
            this.eventExecutor.execute(event);
        }
    }

    @FunctionalInterface
    public interface EventExecutor {
        void execute(PreparedEmote.PreparedEvent event);
    }

    public interface LifecycleListener {
        default void onStart(PreparedEmote animation) {}
        default void onTick(int animationTick) {}
        default void onLoop() {}
        default void onClose(PlaybackStopReason reason) {}
        default void onPositionChanged(int animationTick) {}
        default boolean hasPendingTick() { return false; }
    }

    public interface TimelineTarget {
        Transformation createTransformation(String nodeId, PreparedEmote.PreparedTransform transform);

        default Transformation createTransformation(String nodeId, Matrix4fc matrix, boolean preserveMatrix) {
            return createTransformation(nodeId, PreparedEmote.PreparedTransform.create(new Matrix4f(matrix), preserveMatrix));
        }

        void applyTransform(
            String nodeId,
            PreparedEmote.PreparedTransform transform,
            int interpolationDurationTicks
        );

        default void applyTransform(
            String nodeId,
            Matrix4fc matrix,
            boolean preserveMatrix,
            int interpolationDurationTicks
        ) {
            applyTransform(
                nodeId,
                PreparedEmote.PreparedTransform.create(new Matrix4f(matrix), preserveMatrix),
                interpolationDurationTicks
            );
        }

        void setVisible(String nodeId, boolean visible);

        void applyNbt(String nodeId, CompoundTag nbt);

        default void resetNbt(String nodeId) {}

        void resetAll();
    }

}
