package io.github.hanhy06.emote.playback;

import com.mojang.math.Transformation;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.content.PreparedAnimation;
import io.github.hanhy06.emote.playback.runtime.PlaybackEntityController;
import io.github.hanhy06.emote.playback.runtime.PlaybackNodes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AnimationPlayer {
    private final EmoteAnimation animation;
    private final PreparedAnimation emote;
    private final TimelineTarget target;
    private AnimationRuntime runtime;
    private final Map<String, PreparedAnimation.PreparedTransform> appliedTransforms = new HashMap<>();
    private final Map<String, Boolean> appliedVisibility = new HashMap<>();

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

    public AnimationPlayer(
        PreparedAnimation emote,
        PlaybackNodes nodes,
        PlaybackEntityController entityController
    ) {
        this(emote, new EntityTimelineTarget(emote, nodes, entityController));
    }

    public AnimationPlayer(PreparedAnimation emote, TimelineTarget target) {
        this.emote = Objects.requireNonNull(emote, "emote");
        this.animation = emote.animation();
        this.target = Objects.requireNonNull(target, "target");
        this.runtime = emote.playbackSegments().isEmpty()
            ? new AnimationRuntime(emote)
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
        execute(this.animation.timeline().events().start());
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
            execute(this.animation.timeline().events().loop());
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

    public void stop() {
        if (!this.eventsStarted || this.eventsStopped) {
            return;
        }
        this.eventsStopped = true;
        execute(this.animation.timeline().events().stop());
    }

    public Transformation currentTransformation(String nodeId) {
        PreparedAnimation.PreparedTransform transform = this.runtime == null ? null : this.runtime.currentTransform(nodeId);
        return this.target.createTransformation(
            nodeId,
            transform == null ? this.emote.defaultTransform(nodeId) : transform
        );
    }

    private void resetToTickZero() {
        this.target.resetAll();
        clearState();
        if (this.runtime == null) {
            applyTick(0);
        } else {
            applyPose(this.runtime.beginCycle(0, this.loopCount), 0);
        }
    }

    private void clearState() {
        this.appliedTransforms.clear();
        this.appliedVisibility.clear();
        this.activePlaybackSegment = -1;
        this.mirroredNodes = Map.of();
        if (!this.emote.playbackSegments().isEmpty()) {
            this.runtime = null;
        }
        this.currentTick = 0;
        this.remainingLoopDelay = 0;
    }

    private void applySynchronizedSnapshot(int tick) {
        applyPose(this.runtime.beginCycle(tick, this.loopCount), 0);
    }

    private void applyTick(int tick) {
        if (!this.emote.playbackSegments().isEmpty()) {
            applyPlaybackSegment(tick);
            applyHiddenNodes(tick);
            return;
        }
        applyPose(this.runtime.evaluate(tick, this.loopCount), tick == 0 ? 0 : 1);
    }

    private void applyPlaybackSegment(int tick) {
        List<PreparedAnimation.PlaybackSegment> segments = this.emote.playbackSegments();
        int selected = -1;
        for (int index = 0; index < segments.size(); index++) {
            PreparedAnimation.PlaybackSegment segment = segments.get(index);
            if (segment.startTick() > tick) {
                break;
            }
            if (tick <= segment.endTick()) {
                selected = index;
            }
        }
        if (selected < 0) {
            return;
        }
        PreparedAnimation.PlaybackSegment segment = segments.get(selected);
        int localTick = tick - segment.startTick();
        AnimationRuntime.Pose pose;
        if (selected != this.activePlaybackSegment) {
            this.activePlaybackSegment = selected;
            this.mirroredNodes = segment.mirroredNodes();
            this.runtime = new AnimationRuntime(segment.animation());
            pose = this.runtime.beginCycle(localTick, 0);
        } else {
            pose = this.runtime.evaluate(localTick, 0);
        }
        applyPose(pose, tick == 0 || localTick == 0 ? 0 : 1, this.mirroredNodes);
    }

    private void applyHiddenNodes(int tick) {
        this.emote.hiddenNodes(tick).forEach(nodeId -> applyVisibility(nodeId, false));
    }

    private void applyPose(AnimationRuntime.Pose pose, int interpolationDurationTicks) {
        applyPose(pose, interpolationDurationTicks, Map.of());
    }

    private void applyPose(
        AnimationRuntime.Pose pose,
        int interpolationDurationTicks,
        Map<String, String> mirroredNodes
    ) {
        for (Map.Entry<String, PreparedAnimation.PreparedTransform> entry : pose.transforms().entrySet()) {
            applyTransform(entry.getKey(), entry.getValue(), interpolationDurationTicks);
            String mirror = mirroredNodes.get(entry.getKey());
            if (mirror != null) {
                applyTransform(mirror, entry.getValue(), interpolationDurationTicks);
            }
        }
        for (Map.Entry<String, Boolean> entry : pose.visibility().entrySet()) {
            applyVisibility(entry.getKey(), entry.getValue());
            String mirror = mirroredNodes.get(entry.getKey());
            if (mirror != null) {
                applyVisibility(mirror, entry.getValue());
            }
        }
    }

    private void applyTransform(
        String nodeId,
        PreparedAnimation.PreparedTransform transform,
        int interpolationDurationTicks
    ) {
        PreparedAnimation.PreparedTransform applied = this.appliedTransforms.get(nodeId);
        if (applied != null && transform.hasSameMatrix(applied)) {
            return;
        }
        this.appliedTransforms.put(nodeId, transform);
        this.target.applyTransform(nodeId, transform, interpolationDurationTicks);
    }

    private void applyVisibility(String nodeId, boolean visible) {
        if (Boolean.valueOf(visible).equals(this.appliedVisibility.put(nodeId, visible))) {
            return;
        }
        this.target.setVisible(nodeId, visible);
    }

    public enum AdvanceResult {
        CONTINUE,
        LOOP_BOUNDARY,
        RESTARTED,
        FINISHED
    }

    private void execute(List<EmoteAnimation.Event> events) {
        if (this.eventExecutor == null) {
            return;
        }
        for (EmoteAnimation.Event event : events) {
            this.eventExecutor.execute(event);
        }
    }

    @FunctionalInterface
    public interface EventExecutor {
        void execute(EmoteAnimation.Event event);
    }

    public interface TimelineTarget {
        Transformation createTransformation(String nodeId, PreparedAnimation.PreparedTransform transform);

        void applyTransform(
            String nodeId,
            PreparedAnimation.PreparedTransform transform,
            int interpolationDurationTicks
        );

        void setVisible(String nodeId, boolean visible);

        void resetAll();
    }

    private record EntityTimelineTarget(
        PreparedAnimation emote,
        PlaybackNodes nodes,
        PlaybackEntityController entityController
    ) implements TimelineTarget {
        @Override
        public Transformation createTransformation(String nodeId, PreparedAnimation.PreparedTransform transform) {
            PlaybackNodes.NodeInstance node = requiredNode(nodeId);
            return this.nodes.displayTransformation(node.node().space(), transform);
        }

        @Override
        public void applyTransform(
            String nodeId,
            PreparedAnimation.PreparedTransform transform,
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
        public void setVisible(String nodeId, boolean visible) {
            this.entityController.setVisible(requiredNode(nodeId), this.nodes.requestVisibility(nodeId, visible));
        }

        @Override
        public void resetAll() {
            this.nodes.nodes().forEach((nodeId, node) -> {
                this.entityController.applyTransformation(
                    this.nodes,
                    node,
                    this.emote.defaultTransform(nodeId),
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
