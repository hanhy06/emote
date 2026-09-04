package io.github.hanhy06.emote.playback;

import com.mojang.math.Transformation;
import io.github.hanhy06.emote.api.EmoteCallbackPhase;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.content.PreparedAnimation;
import io.github.hanhy06.emote.playback.molang.MolangQueries;
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
    private final MolangQueries.Source querySource;
    private AnimationEvaluator evaluator;
    private final Map<String, Matrix4f> appliedTransforms = new HashMap<>();
    private final Map<String, Boolean> appliedVisibility = new HashMap<>();
    private final Map<String, CompoundTag> appliedNbt = new HashMap<>();

    private int currentTick;
    private int remainingLoopDelay;
    private int loopCount;
    private int activePlaybackSegment = -1;
    private Map<String, String> mirroredNodes = Map.of();
    private boolean started;
    private boolean finished;
    private boolean awaitingLoopContinuation;
    private boolean initialVisibilityDeferred;
    private EventExecutor eventExecutor;
    private boolean eventsStarted;
    private boolean eventsStopped;

    public AnimationPlayer(PreparedAnimation emote, TimelineTarget target) {
        this(emote, target, MolangQueries.EMPTY);
    }

    public AnimationPlayer(PreparedAnimation emote, TimelineTarget target, MolangQueries.Source querySource) {
        this.emote = Objects.requireNonNull(emote, "emote");
        this.animation = emote.animation();
        this.target = Objects.requireNonNull(target, "target");
        this.querySource = Objects.requireNonNull(querySource, "querySource");
        this.evaluator = emote.playbackSegments().isEmpty()
            ? new AnimationEvaluator(emote, this.querySource)
            : null;
    }

    public void start() {
        if (this.started) {
            throw new IllegalStateException("Timeline already started");
        }
        this.started = true;
        resetToTickZero();
    }

    public void startSynchronized(long serverTick) {
        if (this.started) {
            throw new IllegalStateException("Timeline already started");
        }
        if (this.animation.settings().playback().mode() != EmoteAnimation.LoopMode.SERVER_SYNC) {
            throw new IllegalStateException("Timeline is not server synchronized");
        }

        startAtCyclePhaseUnchecked(serverTick);
    }

    public void startAtCyclePhase(long cycleTick) {
        if (this.started) {
            throw new IllegalStateException("Timeline already started");
        }
        startAtCyclePhaseUnchecked(cycleTick);
    }

    private void startAtCyclePhaseUnchecked(long cycleTick) {
        this.started = true;
        clearState();
        int duration = this.animation.timeline().durationTicks();
        long cycleLength = (long) duration + this.animation.settings().playback().loopDelayTicks();
        long phase = Math.floorMod(cycleTick, cycleLength);
        int timelineTick = (int) Math.min(phase, duration);
        this.currentTick = timelineTick;
        this.loopCount = (int) Math.min(Integer.MAX_VALUE, Math.floorDiv(cycleTick, cycleLength));
        applySynchronizedSnapshot(timelineTick);
        if (phase >= duration) {
            this.remainingLoopDelay = (int) (cycleLength - phase);
        }
    }

    public void deferInitialVisibility() {
        if (!this.started) {
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

    public void startEvents() {
        if (this.eventsStarted) {
            throw new IllegalStateException("Events already started");
        }
        this.eventsStarted = true;
        if (this.emote.playbackSegments().isEmpty()) {
            execute(
                this.animation.timeline().events().start(),
                this.animation.id(),
                this.currentTick,
                EmoteCallbackPhase.START
            );
        }
        if (this.currentTick == 0) {
            execute(this.emote.timelineEvents(0));
        }
    }

    public AdvanceResult advance() {
        return advance(true);
    }

    public AdvanceResult advance(boolean continueAfterLoopBoundary) {
        int previousTick = this.currentTick;
        AdvanceResult result = advanceTimeline();
        if (result != AdvanceResult.RESTARTED && this.currentTick != previousTick) {
            execute(this.emote.timelineEvents(this.currentTick));
        }
        if (result == AdvanceResult.LOOP_BOUNDARY && this.eventsStarted) {
            execute(
                this.animation.timeline().events().loop(),
                this.animation.id(),
                this.animation.timeline().durationTicks(),
                EmoteCallbackPhase.LOOP
            );
            if (continueAfterLoopBoundary) {
                result = continueAfterLoopEvent();
            }
        }
        if (result == AdvanceResult.RESTARTED) {
            execute(this.emote.timelineEvents(this.currentTick));
        }
        return result;
    }

    private AdvanceResult advanceTimeline() {
        if (!this.started) {
            throw new IllegalStateException("Timeline has not started");
        }
        if (this.finished) {
            return AdvanceResult.FINISHED;
        }
        if (this.awaitingLoopContinuation) {
            throw new IllegalStateException("Loop boundary must be continued before advancing");
        }

        if (this.remainingLoopDelay > 0) {
            this.remainingLoopDelay--;
            if (this.remainingLoopDelay == 0) {
                this.loopCount++;
                resetToTickZero();
                return AdvanceResult.RESTARTED;
            }
            return AdvanceResult.CONTINUE;
        }

        if (this.animation.settings().playback().mode() == EmoteAnimation.LoopMode.HOLD
            && this.currentTick >= this.animation.timeline().durationTicks()) {
            return AdvanceResult.CONTINUE;
        }

        this.currentTick++;
        applyTick(this.currentTick);
        if (this.currentTick < this.animation.timeline().durationTicks()) {
            return AdvanceResult.CONTINUE;
        }
        if (this.animation.settings().playback().mode() == EmoteAnimation.LoopMode.ONCE) {
            this.finished = true;
            return AdvanceResult.FINISHED;
        }
        if (this.animation.settings().playback().mode() == EmoteAnimation.LoopMode.HOLD) {
            return AdvanceResult.CONTINUE;
        }
        this.awaitingLoopContinuation = true;
        return AdvanceResult.LOOP_BOUNDARY;
    }

    public AdvanceResult continueAfterLoopEvent() {
        if (!this.awaitingLoopContinuation) {
            throw new IllegalStateException("Timeline is not at a loop boundary");
        }
        this.awaitingLoopContinuation = false;
        int loopDelay = this.animation.settings().playback().loopDelayTicks();
        if (loopDelay == 0) {
            this.loopCount++;
            resetToTickZero();
            return AdvanceResult.RESTARTED;
        }
        this.remainingLoopDelay = loopDelay;
        return AdvanceResult.CONTINUE;
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
        if (!this.eventsStarted || this.eventsStopped) {
            return;
        }
        this.eventsStopped = true;
        if (this.emote.playbackSegments().isEmpty()) {
            execute(
                this.animation.timeline().events().stop(),
                this.animation.id(),
                this.currentTick,
                EmoteCallbackPhase.STOP
            );
            return;
        }
        PreparedAnimation.PlaybackSegment segment = activeLifecycleSegment();
        if (segment != null) {
            execute(
                segment.animation().animation().timeline().events().stop(),
                segment.animation().animation().id(),
                this.currentTick - segment.startTick(),
                EmoteCallbackPhase.STOP
            );
        }
    }

    public Transformation currentTransformation(String nodeId) {
        Matrix4fc matrix = this.evaluator == null ? null : this.evaluator.matrix(nodeId);
        if (matrix == null) return this.target.createTransformation(nodeId, this.emote.defaultTransform(nodeId));
        return this.target.createTransformation(nodeId, matrix, this.evaluator.preservesMatrix(nodeId));
    }

    private void resetToTickZero() {
        this.target.resetAll();
        clearState();
        if (this.evaluator == null) {
            applyTick(0);
        } else {
            this.evaluator.beginCycle(0, this.loopCount);
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
        applyEvaluator(tick == 0 ? 0 : 1, Map.of());
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
        PreparedAnimation.PlaybackSegment segment = segments.get(selected);
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
        applyEvaluator(tick == 0 || localTick == 0 ? 0 : 1, this.mirroredNodes);
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

    private PreparedAnimation.PlaybackSegment activeLifecycleSegment() {
        for (PreparedAnimation.PlaybackSegment segment : this.emote.playbackSegments()) {
            if (this.currentTick >= segment.startTick() && this.currentTick < segment.endTick()) {
                return segment;
            }
        }
        return null;
    }

    private void execute(
        List<EmoteAnimation.Event> events,
        Identifier animationId,
        int animationTick,
        EmoteCallbackPhase phase
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
