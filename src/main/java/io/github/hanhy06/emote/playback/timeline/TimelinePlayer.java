package io.github.hanhy06.emote.playback.timeline;

import com.mojang.math.Transformation;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.content.CompiledTimeline;
import io.github.hanhy06.emote.playback.runtime.PlaybackEntityController;
import io.github.hanhy06.emote.playback.runtime.PlaybackNodes;

import java.util.*;

public final class TimelinePlayer {
    private final EmoteAnimation animation;
    private final CompiledTimeline compiledTimeline;
    private final TimelineTarget target;
    private final Map<String, TransformState> transformStates = new HashMap<>();
    private final Map<String, EmoteAnimation.Matrix> appliedMatrices = new HashMap<>();

    private List<CompiledTimeline.TransformActivation> pendingInterpolations = List.of();
    private CompiledTimeline.TickActions currentTickActions;

    private int currentTick;
    private int remainingLoopDelay;
    private boolean started;
    private boolean finished;
    private boolean awaitingLoopContinuation;
    private boolean initialVisibilityDeferred;

    public TimelinePlayer(
        CompiledTimeline compiledTimeline,
        PlaybackNodes nodes,
        PlaybackEntityController entityController
    ) {
        this(compiledTimeline, new EntityTimelineTarget(compiledTimeline, nodes, entityController));
    }

    public TimelinePlayer(EmoteAnimation animation, TimelineTarget target) {
        this(CompiledTimeline.compile(animation), target);
    }

    public TimelinePlayer(CompiledTimeline compiledTimeline, TimelineTarget target) {
        this.compiledTimeline = Objects.requireNonNull(compiledTimeline, "compiledTimeline");
        this.animation = compiledTimeline.animation();
        this.target = Objects.requireNonNull(target, "target");
        this.currentTickActions = compiledTimeline.tickActions(0);
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
        this.currentTickActions = this.compiledTimeline.tickActions(timelineTick);
        applySynchronizedSnapshot(timelineTick);
        if (phase >= duration) {
            this.remainingLoopDelay = (int) (cycleLength - phase);
        }
    }

    public void resumeSynchronizedInterpolation() {
        if (!this.started || this.animation.settings().playback().mode() != EmoteAnimation.LoopMode.SERVER_SYNC) {
            throw new IllegalStateException("Synchronized timeline has not started");
        }
        resumeInitialInterpolation();
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
            this.compiledTimeline.visible(nodeId, this.currentTick, node.visible())
        ));
    }

    public void resumeInitialInterpolation() {
        if (!this.started) {
            throw new IllegalStateException("Timeline has not started");
        }
        resumePendingInterpolations();
    }

    public AdvanceResult advance() {
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
        resumePendingInterpolations();
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
            resetToTickZero();
            return AdvanceResult.RESTARTED;
        }
        this.remainingLoopDelay = loopDelay;
        return AdvanceResult.CONTINUE;
    }

    public int currentTick() {
        return this.currentTick;
    }

    List<EmoteAnimation.Event> currentTimelineEvents() {
        return this.currentTickActions.events();
    }

    public Transformation currentTransformation(String nodeId) {
        TransformState state = this.transformStates.get(nodeId);
        if (state == null) {
            return this.target.createTransformation(nodeId, this.compiledTimeline.defaultTransform(nodeId));
        }
        return state.at(this.currentTick);
    }

    private void resetToTickZero() {
        this.target.resetAll();
        clearState();
        applyTick(0);
    }

    private void clearState() {
        this.transformStates.clear();
        this.appliedMatrices.clear();
        this.pendingInterpolations = List.of();
        this.currentTickActions = this.compiledTimeline.tickActions(0);
        this.currentTick = 0;
        this.remainingLoopDelay = 0;
    }

    private void applySynchronizedSnapshot(int tick) {
        List<CompiledTimeline.TransformActivation> activeInterpolations = new ArrayList<>();
        for (Map.Entry<String, EmoteAnimation.Node> entry : this.animation.nodes().entrySet()) {
            String nodeId = entry.getKey();
            EmoteAnimation.Node node = entry.getValue();
            CompiledTimeline.TransformActivation activation = this.compiledTimeline.activeTransform(nodeId, tick);
            Transformation currentTransformation;
            if (activation == null) {
                currentTransformation = this.target.createTransformation(nodeId, this.compiledTimeline.defaultTransform(nodeId));
            } else {
                Transformation previous = this.target.createTransformation(nodeId, activation.previousTransform());
                Transformation targetTransformation = this.target.createTransformation(nodeId, activation.transform());
                TransformState state = new TransformState(
                    previous,
                    targetTransformation,
                    activation.activationTick(),
                    activation.interpolationDurationTicks()
                );
                this.transformStates.put(nodeId, state);
                this.appliedMatrices.put(nodeId, activation.transform().matrix());
                currentTransformation = state.at(tick);
                if (activation.interpolationDurationTicks() > 0 && tick < activation.targetTick()) {
                    activeInterpolations.add(activation);
                }
            }
            this.target.setTransformation(nodeId, currentTransformation);
            this.target.setVisible(nodeId, this.compiledTimeline.visible(nodeId, tick, node.visible()));
        }
        this.pendingInterpolations = List.copyOf(activeInterpolations);
    }

    private void resumePendingInterpolations() {
        if (this.pendingInterpolations.isEmpty()) {
            return;
        }
        for (CompiledTimeline.TransformActivation activation : this.pendingInterpolations) {
            this.target.applyTransform(
                activation.nodeId(),
                activation.transform(),
                Math.max(0, activation.targetTick() - this.currentTick)
            );
        }
        this.pendingInterpolations = List.of();
    }

    private void applyTick(int tick) {
        this.currentTickActions = this.compiledTimeline.tickActions(tick);
        for (CompiledTimeline.TransformActivation activation : this.currentTickActions.transforms()) {
            EmoteAnimation.Matrix matrix = activation.transform().matrix();
            if (matrix.equals(this.appliedMatrices.get(activation.nodeId()))) {
                continue;
            }
            Transformation previous = currentTransformation(activation.nodeId());
            Transformation next = this.target.createTransformation(activation.nodeId(), activation.transform());
            this.transformStates.put(
                activation.nodeId(),
                new TransformState(previous, next, tick, activation.interpolationDurationTicks())
            );
            this.appliedMatrices.put(activation.nodeId(), matrix);
            this.target.applyTransform(
                activation.nodeId(),
                activation.transform(),
                activation.interpolationDurationTicks()
            );
        }
        for (CompiledTimeline.StateActivation activation : this.currentTickActions.states()) {
            this.target.setVisible(activation.nodeId(), activation.state().visible());
        }
    }

    public enum AdvanceResult {
        CONTINUE,
        LOOP_BOUNDARY,
        RESTARTED,
        FINISHED
    }

    public interface TimelineTarget {
        Transformation createTransformation(String nodeId, CompiledTimeline.PreparedTransform transform);

        void applyTransform(
            String nodeId,
            CompiledTimeline.PreparedTransform transform,
            int interpolationDurationTicks
        );

        void setTransformation(String nodeId, Transformation transformation);

        void setVisible(String nodeId, boolean visible);

        void resetAll();
    }

    private record TransformState(
        Transformation previous,
        Transformation target,
        int startTick,
        int durationTicks
    ) {
        private Transformation at(int tick) {
            if (this.durationTicks == 0) {
                return this.target;
            }
            float progress = Math.clamp((float) (tick - this.startTick) / this.durationTicks, 0.0F, 1.0F);
            return this.previous.slerp(this.target, progress);
        }
    }

    private record EntityTimelineTarget(
        CompiledTimeline compiledTimeline,
        PlaybackNodes nodes,
        PlaybackEntityController entityController
    ) implements TimelineTarget {
        @Override
        public Transformation createTransformation(String nodeId, CompiledTimeline.PreparedTransform transform) {
            PlaybackNodes.NodeInstance node = requiredNode(nodeId);
            return this.nodes.displayTransformation(node.node().space(), transform);
        }

        @Override
        public void applyTransform(
            String nodeId,
            CompiledTimeline.PreparedTransform transform,
            int interpolationDurationTicks
        ) {
            this.entityController.applyTransformation(
                this.nodes,
                requiredNode(nodeId),
                transform,
                interpolationDurationTicks
            );
        }

        @Override
        public void setTransformation(String nodeId, Transformation transformation) {
            this.entityController.applyTransformation(requiredNode(nodeId), transformation, 0);
        }

        @Override
        public void setVisible(String nodeId, boolean visible) {
            this.entityController.setVisible(requiredNode(nodeId), this.nodes.requestVisibility(nodeId, visible));
        }

        @Override
        public void resetAll() {
            this.nodes.nodes().forEach((nodeId, node) -> {
                this.entityController.applyTransformation(
                    this.nodes,
                    node,
                    this.compiledTimeline.defaultTransform(nodeId),
                    0
                );
                this.entityController.setVisible(node, this.nodes.requestVisibility(nodeId, node.node().visible()));
            });
        }

        private PlaybackNodes.NodeInstance requiredNode(String nodeId) {
            PlaybackNodes.NodeInstance node = this.nodes.nodes().get(nodeId);
            if (node == null) {
                throw new IllegalStateException("Missing playback node: " + nodeId);
            }
            return node;
        }
    }
}
