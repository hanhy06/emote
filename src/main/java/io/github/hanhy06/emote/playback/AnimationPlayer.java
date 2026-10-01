package io.github.hanhy06.emote.playback;

import com.mojang.math.Transformation;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.content.AnimationEventPhase;
import io.github.hanhy06.emote.content.PreparedAnimation;
import io.github.hanhy06.emote.playback.molang.PlayerMolangQueries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AnimationPlayer {
    private final EmoteAnimation animation;
    private final PreparedAnimation emote;
    private final TimelineTarget target;
    private final PlayerMolangQueries.Source querySource;
    private AnimationEvaluator evaluator;
    private final Map<String, Matrix4f> appliedTransforms = new HashMap<>();
    private final Map<String, Boolean> appliedVisibility = new HashMap<>();
    private final Map<String, CompoundTag> appliedNbt = new HashMap<>();

    private int currentTick;
    private int remainingLoopDelay;
    private int loopCount;
    private int activePlaybackSegment = -1;
    private Map<String, String> mirroredNodes = Map.of();
    private PlaybackPhase phase = PlaybackPhase.NOT_STARTED;
    private boolean initialVisibilityDeferred;
    private EventExecutor eventExecutor;
    private boolean eventsStarted;
    private boolean eventsStopped;
    private LifecycleListener lifecycleListener = new LifecycleListener() {};
    private int lifecycleSegment = -1;
    private boolean pendingLoopCallback;

    public AnimationPlayer(PreparedAnimation emote, TimelineTarget target) {
        this(emote, target, PlayerMolangQueries.EMPTY);
    }

    public AnimationPlayer(PreparedAnimation emote, TimelineTarget target, PlayerMolangQueries.Source querySource) {
        this.emote = Objects.requireNonNull(emote, "emote");
        this.animation = emote.animation();
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
        if (this.animation.settings().playback().mode() != EmoteAnimation.LoopMode.SERVER_SYNC) {
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
        int duration = this.animation.timeline().durationTicks();
        EmoteAnimation.PlaybackSettings playback = this.animation.settings().playback();
        int cycleStart = playback.mode() == EmoteAnimation.LoopMode.LOOP ? playback.loopStartTicks() : 0;
        int cycleEnd = playback.mode() == EmoteAnimation.LoopMode.LOOP ? playback.loopEndTicks() : duration;
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
        this.animation.nodes().keySet().forEach(nodeId -> this.target.setVisible(nodeId, false));
        this.initialVisibilityDeferred = true;
    }

    public void restoreDeferredVisibility() {
        if (!this.initialVisibilityDeferred) {
            return;
        }
        this.initialVisibilityDeferred = false;
        this.animation.nodes().forEach((nodeId, node) -> this.target.setVisible(
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
            startSegmentEvents();
            return;
        }
        execute(
            this.animation.timeline().events().start(),
            this.animation.id(),
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
                this.animation.timeline().events().loop(),
                this.animation.id(),
                this.animation.settings().playback().loopEndTicks(),
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
            PreparedAnimation.PlaybackSegment segment = this.emote.playbackSegments().get(this.lifecycleSegment);
            int localTick = this.currentTick - segment.startTick();
            applySegment(this.lifecycleSegment, this.currentTick);
            if (this.currentTick < segment.endTick()) applyHiddenNodes(this.currentTick);
            execute(segment.animation().timelineEvents(localTick));
            if (this.eventsStopped) return AdvanceResult.FINISHED;
            this.lifecycleListener.onTick(localTick);
            if (this.eventsStopped) return AdvanceResult.FINISHED;
            if (this.currentTick >= segment.endTick()) {
                EmoteAnimation source = segment.animation().animation();
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
        if (this.currentTick >= this.animation.timeline().durationTicks()) {
            this.phase = PlaybackPhase.FINISHED;
            return AdvanceResult.FINISHED;
        }
        return this.eventsStopped ? AdvanceResult.FINISHED : AdvanceResult.CONTINUE;
    }

    private void startSegmentEvents() {
        if (this.lifecycleSegment >= 0 || this.eventsStopped) return;
        for (int index = 0; index < this.emote.playbackSegments().size(); index++) {
            PreparedAnimation.PlaybackSegment segment = this.emote.playbackSegments().get(index);
            if (segment.startTick() != this.currentTick) continue;
            this.lifecycleSegment = index;
            EmoteAnimation source = segment.animation().animation();
            execute(source.timeline().events().start(), source.id(), 0, AnimationEventPhase.START);
            if (this.eventsStopped) return;
            execute(segment.animation().timelineEvents(0));
            if (!this.eventsStopped) this.lifecycleListener.onStart(segment.animation());
            return;
        }
    }

    private void closeSegment(PlaybackStopReason reason) {
        if (this.lifecycleSegment < 0) return;
        PreparedAnimation.PlaybackSegment segment = this.emote.playbackSegments().get(this.lifecycleSegment);
        this.lifecycleSegment = -1;
        EmoteAnimation source = segment.animation().animation();
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
            return AdvanceResult.CONTINUE;
        }

        this.currentTick++;
        applyTick(this.currentTick);
        if (this.phase != PlaybackPhase.OUTRO
            && this.animation.settings().playback().mode() == EmoteAnimation.LoopMode.LOOP
            && this.currentTick >= this.animation.settings().playback().loopEndTicks()) {
            this.phase = PlaybackPhase.LOOP_BOUNDARY;
            return AdvanceResult.LOOP_BOUNDARY;
        }
        if (this.currentTick < this.animation.timeline().durationTicks()) {
            return AdvanceResult.CONTINUE;
        }
        if (this.phase == PlaybackPhase.OUTRO || this.animation.settings().playback().mode() == EmoteAnimation.LoopMode.ONCE) {
            this.phase = PlaybackPhase.FINISHED;
            return AdvanceResult.FINISHED;
        }
        if (this.animation.settings().playback().mode() == EmoteAnimation.LoopMode.HOLD) {
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
        int loopDelay = this.animation.settings().playback().loopDelayTicks();
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

    public OutroRequestResult requestOutro() {
        int loopEnd = this.animation.settings().playback().loopEndTicks();
        if (this.animation.settings().playback().mode() != EmoteAnimation.LoopMode.LOOP
            || loopEnd >= this.animation.timeline().durationTicks()
            || this.phase == PlaybackPhase.NOT_STARTED
            || this.phase == PlaybackPhase.FINISHED) {
            return OutroRequestResult.UNSUPPORTED;
        }
        if (this.phase == PlaybackPhase.OUTRO) {
            return OutroRequestResult.ALREADY_RUNNING;
        }

        this.phase = PlaybackPhase.OUTRO;
        this.pendingLoopCallback = false;
        this.remainingLoopDelay = 0;
        if (this.currentTick < loopEnd) {
            this.currentTick = loopEnd;
            applyTick(loopEnd);
            if (this.eventsStarted) execute(this.emote.timelineEvents(loopEnd));
        }
        return OutroRequestResult.STARTED;
    }

    public int currentTick() {
        return this.currentTick;
    }

    public Identifier emoteId() {
        return this.animation.id();
    }

    public float rotationDeadzone() {
        if (this.activePlaybackSegment < 0) {
            return this.animation.settings().rotationDeadzone();
        }
        return this.emote.playbackSegments().get(this.activePlaybackSegment)
            .animation().animation().settings().rotationDeadzone();
    }

    public void stop() {
        stop(PlaybackStopReason.MANUAL);
    }

    public void stop(PlaybackStopReason reason) {
        if (!this.eventsStarted || this.eventsStopped) {
            return;
        }
        this.eventsStopped = true;
        if (this.emote.playbackSegments().isEmpty()) {
            execute(
                this.animation.timeline().events().stop(),
                this.animation.id(),
                this.currentTick,
                AnimationEventPhase.STOP
            );
            return;
        }
        closeSegment(reason);
    }

    public Transformation currentTransformation(String nodeId) {
        Matrix4fc matrix = this.evaluator == null ? null : this.evaluator.matrix(nodeId);
        if (matrix == null) return this.target.createTransformation(nodeId, this.emote.defaultTransform(nodeId));
        return this.target.createTransformation(nodeId, matrix, this.evaluator.preservesMatrix(nodeId));
    }

    private void resetToLoopStart() {
        int tick = this.animation.settings().playback().loopStartTicks();
        if (!this.emote.playbackSegments().isEmpty()) {
            resetToTick(tick);
            return;
        }
        this.currentTick = tick;
        this.evaluator.rewindLoop(tick, this.loopCount);
        applyEvaluator(this.evaluator.displayInterpolationTicks(), Map.of());
    }

    private void resetToTick(int tick) {
        this.target.resetAll();
        clearState();
        this.currentTick = tick;
        if (this.evaluator == null) {
            applyTick(tick);
        } else {
            this.evaluator.beginCycle(tick, this.loopCount);
            applyEvaluator(0, Map.of());
        }
    }

    private void clearState() {
        this.appliedTransforms.clear();
        this.appliedVisibility.clear();
        this.appliedNbt.clear();
        this.activePlaybackSegment = -1;
        this.mirroredNodes = Map.of();
        if (!this.emote.playbackSegments().isEmpty()) {
            this.evaluator = null;
        }
        this.currentTick = 0;
        this.remainingLoopDelay = 0;
    }

    private void applySynchronizedSnapshot(int tick) {
        this.evaluator.beginCycle(tick, this.loopCount);
        applyEvaluator(0, Map.of());
    }

    private void applyTick(int tick) {
        if (!this.emote.playbackSegments().isEmpty()) {
            applyPlaybackSegment(tick);
            applyHiddenNodes(tick);
            return;
        }
        this.evaluator.evaluate(tick, this.loopCount);
        applyEvaluator(tick == 0 ? 0 : this.evaluator.displayInterpolationTicks(), Map.of());
    }

    private void applyPlaybackSegment(int tick) {
        List<PreparedAnimation.PlaybackSegment> segments = this.emote.playbackSegments();
        int selected = this.activePlaybackSegment;
        if (selected >= 0 && tick > segments.get(selected).endTick()) {
            selected = -1;
        }
        for (int index = this.activePlaybackSegment + 1; index < segments.size(); index++) {
            PreparedAnimation.PlaybackSegment next = segments.get(index);
            if (next.transitionStartTick() > tick) break;
            if (tick <= next.endTick()) selected = index;
        }
        if (selected < 0) {
            return;
        }
        applySegment(selected, tick);
    }

    private void applySegment(int selected, int tick) {
        PreparedAnimation.PlaybackSegment segment = this.emote.playbackSegments().get(selected);
        int localTick = tick - segment.startTick();
        boolean segmentChanged = selected != this.activePlaybackSegment;
        if (segmentChanged) {
            this.activePlaybackSegment = selected;
            this.mirroredNodes = segment.mirroredNodes();
            this.evaluator = new AnimationEvaluator(segment.animation(), this.querySource);
            this.evaluator.beginCycle(Math.max(localTick, 0), 0);
        } else if (localTick > 0) {
            this.evaluator.evaluate(localTick, 0);
        }
        if (localTick < 0) {
            if (segmentChanged) {
                applyEvaluatorTransforms(segment.startTick() - segment.transitionStartTick(), this.mirroredNodes);
            }
            return;
        }
        applyEvaluator(tick == 0 || localTick == 0 ? 0 : this.evaluator.displayInterpolationTicks(), this.mirroredNodes);
    }

    private void applyHiddenNodes(int tick) {
        this.emote.hiddenNodes(tick).forEach(nodeId -> applyVisibility(nodeId, false));
    }

    private void applyEvaluator(int interpolationDurationTicks, Map<String, String> mirroredNodes) {
        for (int index = 0; index < this.evaluator.nodeCount(); index++) {
            String nodeId = applyEvaluatorTransform(index, interpolationDurationTicks);
            String mirror = mirroredNodes.get(nodeId);
            if (mirror != null) {
                applyTransform(
                    mirror,
                    this.evaluator.matrix(index),
                    this.evaluator.preservesMatrix(index),
                    interpolationDurationTicks
                );
            }
            boolean visible = this.evaluator.visible(index);
            applyVisibility(nodeId, visible);
            if (mirror != null) {
                applyVisibility(mirror, visible);
            }
            CompoundTag nbt = this.evaluator.nbt(index);
            if (nbt != null) {
                applyNbt(nodeId, nbt);
                if (mirror != null) applyNbt(mirror, nbt);
            }
        }
    }

    private void applyEvaluatorTransforms(int interpolationDurationTicks, Map<String, String> mirroredNodes) {
        for (int index = 0; index < this.evaluator.nodeCount(); index++) {
            String nodeId = applyEvaluatorTransform(index, interpolationDurationTicks);
            String mirror = mirroredNodes.get(nodeId);
            if (mirror != null) {
                applyTransform(
                    mirror,
                    this.evaluator.matrix(index),
                    this.evaluator.preservesMatrix(index),
                    interpolationDurationTicks
                );
            }
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

    public enum OutroRequestResult {
        STARTED,
        ALREADY_RUNNING,
        UNSUPPORTED
    }

    private enum PlaybackPhase {
        NOT_STARTED,
        RUNNING,
        LOOP_BOUNDARY,
        LOOP_DELAY,
        OUTRO,
        HOLDING,
        FINISHED
    }

    private void execute(
        List<EmoteAnimation.Event> events,
        Identifier animationId,
        int animationTick,
        AnimationEventPhase phase
    ) {
        execute(events.stream().map(event -> new PreparedAnimation.PreparedEvent(event, animationId, animationTick, phase)).toList());
    }

    private void execute(List<PreparedAnimation.PreparedEvent> events) {
        if (this.eventExecutor == null) {
            return;
        }
        for (PreparedAnimation.PreparedEvent event : events) {
            this.eventExecutor.execute(event);
        }
    }

    @FunctionalInterface
    public interface EventExecutor {
        void execute(PreparedAnimation.PreparedEvent event);
    }

    public interface LifecycleListener {
        default void onStart(PreparedAnimation animation) {}
        default void onTick(int animationTick) {}
        default void onLoop() {}
        default void onClose(PlaybackStopReason reason) {}
    }

    public interface TimelineTarget {
        Transformation createTransformation(String nodeId, PreparedAnimation.PreparedTransform transform);

        default Transformation createTransformation(String nodeId, Matrix4fc matrix, boolean preserveMatrix) {
            return createTransformation(nodeId, PreparedAnimation.PreparedTransform.create(new Matrix4f(matrix), preserveMatrix));
        }

        void applyTransform(
            String nodeId,
            PreparedAnimation.PreparedTransform transform,
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
                PreparedAnimation.PreparedTransform.create(new Matrix4f(matrix), preserveMatrix),
                interpolationDurationTicks
            );
        }

        void setVisible(String nodeId, boolean visible);

        void applyNbt(String nodeId, CompoundTag nbt);

        void resetAll();
    }

}
