package io.github.hanhy06.emote.playback;

import com.mojang.math.Transformation;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;

import java.util.*;

public final class TimelinePlayer {
    private final EmoteAnimation animation;
    private final PlaybackPlan playbackPlan;
    private final TimelineTarget target;
    private final Map<String, TransformState> transformStates = new HashMap<>();
    private final Map<String, EmoteAnimation.Matrix> appliedMatrices = new HashMap<>();
    private List<PlaybackPlan.TransformActivation> pendingInterpolations = List.of();

    private int currentTick;
    private int remainingLoopDelay;
    private boolean started;
    private boolean finished;
    private boolean awaitingLoopContinuation;

    public TimelinePlayer(
        PlaybackPlan playbackPlan,
        PlaybackNodes nodes,
        PlaybackEntityController entityController
    ) {
        this(playbackPlan, new EntityTimelineTarget(nodes, entityController));
    }

    TimelinePlayer(EmoteAnimation animation, TimelineTarget target) {
        this(PlaybackPlan.compile(animation), target);
    }

    TimelinePlayer(PlaybackPlan playbackPlan, TimelineTarget target) {
        this.playbackPlan = Objects.requireNonNull(playbackPlan, "playbackPlan");
        this.animation = playbackPlan.animation();
        this.target = Objects.requireNonNull(target, "target");
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
        if (this.animation.timeline().loop() != EmoteAnimation.LoopMode.SERVER_SYNC) {
            throw new IllegalStateException("Timeline is not server synchronized");
        }

        this.started = true;
        clearState();
        int duration = this.animation.timeline().durationTicks();
        long cycleLength = (long)duration + this.animation.timeline().loopDelayTicks();
        long phase = Math.floorMod(serverTick, cycleLength);
        int timelineTick = (int)Math.min(phase, duration);
        this.currentTick = timelineTick;
        applySynchronizedSnapshot(timelineTick);
        if (phase >= duration) {
            this.remainingLoopDelay = (int)(cycleLength - phase);
        }
    }

    public void resumeSynchronizedInterpolation() {
        if (!this.started || this.animation.timeline().loop() != EmoteAnimation.LoopMode.SERVER_SYNC) {
            throw new IllegalStateException("Synchronized timeline has not started");
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

        this.currentTick++;
        resumePendingInterpolations();
        applyTick(this.currentTick);
        if (this.currentTick < this.animation.timeline().durationTicks()) {
            return AdvanceResult.CONTINUE;
        }
        if (this.animation.timeline().loop() == EmoteAnimation.LoopMode.ONCE) {
            this.finished = true;
            return AdvanceResult.FINISHED;
        }
        this.awaitingLoopContinuation = true;
        return AdvanceResult.LOOP_BOUNDARY;
    }

    public AdvanceResult continueAfterLoopEvent() {
        if (!this.awaitingLoopContinuation) {
            throw new IllegalStateException("Timeline is not at a loop boundary");
        }
        this.awaitingLoopContinuation = false;
        int loopDelay = this.animation.timeline().loopDelayTicks();
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

    public Transformation currentTransformation(String nodeId) {
        TransformState state = this.transformStates.get(nodeId);
        if (state == null) {
            return this.target.createTransformation(this.animation.nodes().get(nodeId).defaultMatrix());
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
        this.currentTick = 0;
        this.remainingLoopDelay = 0;
    }

    private void applySynchronizedSnapshot(int tick) {
        List<PlaybackPlan.TransformActivation> activeInterpolations = new ArrayList<>();
        for (Map.Entry<String, EmoteAnimation.Node> entry : this.animation.nodes().entrySet()) {
            String nodeId = entry.getKey();
            EmoteAnimation.Node node = entry.getValue();
            PlaybackPlan.TransformActivation activation = this.playbackPlan.activeTransform(nodeId, tick);
            Transformation currentTransformation;
            if (activation == null) {
                currentTransformation = this.target.createTransformation(node.defaultMatrix());
            } else {
                Transformation previous = this.target.createTransformation(activation.previousMatrix());
                Transformation targetTransformation = this.target.createTransformation(activation.transform().matrix());
                TransformState state = new TransformState(
                    previous,
                    targetTransformation,
                    activation.activationTick(),
                    activation.transform().interpolationDurationTicks()
                );
                this.transformStates.put(nodeId, state);
                this.appliedMatrices.put(nodeId, activation.transform().matrix());
                currentTransformation = state.at(tick);
                if (activation.transform().interpolationDurationTicks() > 0 && tick < activation.targetTick()) {
                    activeInterpolations.add(activation);
                }
            }
            this.target.setTransformation(nodeId, currentTransformation);
            this.target.setVisible(nodeId, this.playbackPlan.visible(nodeId, tick, node.visible()));
        }
        this.pendingInterpolations = List.copyOf(activeInterpolations);
    }

    private void resumePendingInterpolations() {
        if (this.pendingInterpolations.isEmpty()) {
            return;
        }
        for (PlaybackPlan.TransformActivation activation : this.pendingInterpolations) {
            this.target.applyTransform(
                activation.nodeId(),
                activation.transform().matrix(),
                Math.max(0, activation.targetTick() - this.currentTick)
            );
        }
        this.pendingInterpolations = List.of();
    }

    private void applyTick(int tick) {
        for (PlaybackPlan.TransformActivation activation : this.playbackPlan.transformActivations(tick)) {
            EmoteAnimation.Matrix matrix = activation.transform().matrix();
            if (matrix.equals(this.appliedMatrices.get(activation.nodeId()))) {
                continue;
            }
            Transformation previous = currentTransformation(activation.nodeId());
            Transformation next = this.target.createTransformation(matrix);
            this.transformStates.put(
                activation.nodeId(),
                new TransformState(previous, next, tick, activation.transform().interpolationDurationTicks())
            );
            this.appliedMatrices.put(activation.nodeId(), matrix);
            this.target.applyTransform(
                activation.nodeId(),
                matrix,
                activation.transform().interpolationDurationTicks()
            );
        }
        for (PlaybackPlan.StateActivation activation : this.playbackPlan.stateActivations(tick)) {
            this.target.setVisible(activation.nodeId(), activation.state().visible());
        }
    }

    public enum AdvanceResult {
        CONTINUE,
        LOOP_BOUNDARY,
        RESTARTED,
        FINISHED
    }

    interface TimelineTarget {
        Transformation createTransformation(EmoteAnimation.Matrix matrix);

        void applyTransform(String nodeId, EmoteAnimation.Matrix matrix, int interpolationDurationTicks);

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
            float progress = Math.clamp((float)(tick - this.startTick) / this.durationTicks, 0.0F, 1.0F);
            return this.previous.slerp(this.target, progress);
        }
    }

    private record EntityTimelineTarget(
        PlaybackNodes nodes,
        PlaybackEntityController entityController
    ) implements TimelineTarget {
        @Override
        public Transformation createTransformation(EmoteAnimation.Matrix matrix) {
            return new Transformation(this.nodes.root().displayMatrix(matrix));
        }

        @Override
        public void applyTransform(String nodeId, EmoteAnimation.Matrix matrix, int interpolationDurationTicks) {
            this.entityController.applyTransformation(
                this.nodes,
                requiredNode(nodeId),
                matrix,
                interpolationDurationTicks
            );
        }

        @Override
        public void setTransformation(String nodeId, Transformation transformation) {
            this.entityController.applyTransformation(requiredNode(nodeId), transformation, 0);
        }

        @Override
        public void setVisible(String nodeId, boolean visible) {
            this.entityController.setVisible(requiredNode(nodeId), visible);
        }

        @Override
        public void resetAll() {
            this.nodes.nodes().values().forEach(node -> this.entityController.resetNode(this.nodes, node));
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
