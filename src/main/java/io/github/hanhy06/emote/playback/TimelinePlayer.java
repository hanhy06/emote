package io.github.hanhy06.emote.playback;

import com.mojang.math.Transformation;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;

import java.util.*;

public final class TimelinePlayer {
    private final EmoteAnimation animation;
    private final TimelineTarget target;
    private final Map<Integer, List<TransformActivation>> transformActivations;
    private final Map<Integer, List<StateActivation>> stateActivations;
    private final Map<String, TransformState> transformStates = new HashMap<>();
    private List<TransformActivation> pendingInterpolations = List.of();

    private int currentTick;
    private int remainingLoopDelay;
    private boolean started;
    private boolean finished;
    private boolean awaitingLoopContinuation;

    public TimelinePlayer(
        EmoteAnimation animation,
        PlaybackNodes nodes,
        PlaybackEntityController entityController
    ) {
        this(animation, new EntityTimelineTarget(nodes, entityController));
    }

    TimelinePlayer(EmoteAnimation animation, TimelineTarget target) {
        this.animation = Objects.requireNonNull(animation, "animation");
        this.target = Objects.requireNonNull(target, "target");
        this.transformActivations = createTransformActivations(animation.timeline().keyframes());
        this.stateActivations = createStateActivations(animation.timeline().keyframes());
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
        resetToTickZero();
        int duration = this.animation.timeline().durationTicks();
        long cycleLength = (long)duration + this.animation.timeline().loopDelayTicks();
        long phase = Math.floorMod(serverTick, cycleLength);
        int timelineTick = (int)Math.min(phase, duration);
        for (int tick = 1; tick <= timelineTick; tick++) {
            this.currentTick = tick;
            applyTick(tick);
        }
        if (phase >= duration) {
            this.remainingLoopDelay = (int)(cycleLength - phase);
        } else {
            this.pendingInterpolations = activeInterpolationsAt((int)phase);
        }
        for (String nodeId : this.animation.nodes().keySet()) {
            this.target.setTransformation(nodeId, currentTransformation(nodeId));
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
        this.transformStates.clear();
        this.currentTick = 0;
        this.remainingLoopDelay = 0;
        applyTick(0);
    }

    private List<TransformActivation> activeInterpolationsAt(int tick) {
        return this.transformActivations.entrySet().stream()
            .filter(entry -> entry.getKey() <= tick)
            .flatMap(entry -> entry.getValue().stream())
            .filter(activation -> activation.transform().interpolationDurationTicks() > 0)
            .filter(activation -> tick < activation.targetTick())
            .toList();
    }

    private void resumePendingInterpolations() {
        if (this.pendingInterpolations.isEmpty()) {
            return;
        }
        for (TransformActivation activation : this.pendingInterpolations) {
            this.target.applyTransform(
                activation.nodeId(),
                activation.transform().matrix(),
                Math.max(0, activation.targetTick() - this.currentTick)
            );
        }
        this.pendingInterpolations = List.of();
    }

    private void applyTick(int tick) {
        for (TransformActivation activation : this.transformActivations.getOrDefault(tick, List.of())) {
            Transformation previous = currentTransformation(activation.nodeId());
            Transformation next = this.target.createTransformation(activation.transform().matrix());
            this.transformStates.put(
                activation.nodeId(),
                new TransformState(previous, next, tick, activation.transform().interpolationDurationTicks())
            );
            this.target.applyTransform(
                activation.nodeId(),
                activation.transform().matrix(),
                activation.transform().interpolationDurationTicks()
            );
        }
        for (StateActivation activation : this.stateActivations.getOrDefault(tick, List.of())) {
            this.target.setVisible(activation.nodeId(), activation.state().visible());
        }
    }

    private Map<Integer, List<TransformActivation>> createTransformActivations(List<EmoteAnimation.Keyframe> keyframes) {
        Map<Integer, List<TransformActivation>> activations = new HashMap<>();
        for (EmoteAnimation.Keyframe keyframe : keyframes) {
            for (Map.Entry<String, EmoteAnimation.NodeTransform> entry : keyframe.nodeTransforms().entrySet()) {
                int activationTick = keyframe.tick() - entry.getValue().interpolationDurationTicks();
                activations.computeIfAbsent(activationTick, ignored -> new ArrayList<>())
                    .add(new TransformActivation(keyframe.tick(), entry.getKey(), entry.getValue()));
            }
        }
        activations.values().forEach(list -> list.sort(Comparator.comparingInt(TransformActivation::targetTick)));
        return copyActivationMap(activations);
    }

    private Map<Integer, List<StateActivation>> createStateActivations(List<EmoteAnimation.Keyframe> keyframes) {
        Map<Integer, List<StateActivation>> activations = new HashMap<>();
        for (EmoteAnimation.Keyframe keyframe : keyframes) {
            for (Map.Entry<String, EmoteAnimation.NodeState> entry : keyframe.nodeStates().entrySet()) {
                activations.computeIfAbsent(keyframe.tick(), ignored -> new ArrayList<>())
                    .add(new StateActivation(entry.getKey(), entry.getValue()));
            }
        }
        return copyActivationMap(activations);
    }

    private <T> Map<Integer, List<T>> copyActivationMap(Map<Integer, List<T>> source) {
        Map<Integer, List<T>> copied = new HashMap<>();
        source.forEach((tick, values) -> copied.put(tick, List.copyOf(values)));
        return Map.copyOf(copied);
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

    private record TransformActivation(
        int targetTick,
        String nodeId,
        EmoteAnimation.NodeTransform transform
    ) {
    }

    private record StateActivation(String nodeId, EmoteAnimation.NodeState state) {
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
