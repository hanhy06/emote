package io.github.hanhy06.emote.playback;

import com.mojang.math.Transformation;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.content.PreparedAnimation;
import io.github.hanhy06.emote.playback.molang.PlayerMolangQueries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4fc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

public final class SequencePlayer extends AnimationPlayer {
    private final PreparedAnimation sequence;
    private final TimelineTarget target;
    private final PlayerMolangQueries.Source querySource;
    private final LongSupplier serverTick;
    private final List<PreparedAnimation.PlaybackSegment> segments;

    private int currentTick;
    private int nextSegmentIndex;
    private AnimationPlayer activeAnimation;
    private SequenceTimelineTarget activeTarget;
    private PreparedAnimation.PlaybackSegment activeSegment;
    private EventExecutor eventExecutor;
    private boolean started;
    private boolean eventsStarted;
    private boolean initialVisibilityDeferred;
    private boolean transitioning;
    private boolean finished;

    public SequencePlayer(
        PreparedAnimation sequence,
        TimelineTarget target,
        PlayerMolangQueries.Source querySource,
        LongSupplier serverTick
    ) {
        super(sequence, target, querySource);
        this.sequence = Objects.requireNonNull(sequence, "sequence");
        this.target = Objects.requireNonNull(target, "target");
        this.querySource = Objects.requireNonNull(querySource, "querySource");
        this.serverTick = Objects.requireNonNull(serverTick, "serverTick");
        this.segments = sequence.playbackSegments();
    }

    @Override
    public void bindEvents(EventExecutor eventExecutor) {
        if (this.eventExecutor != null) {
            throw new IllegalStateException("Animation events are already bound");
        }
        this.eventExecutor = Objects.requireNonNull(eventExecutor, "eventExecutor");
    }

    @Override
    public void start() {
        if (this.started) {
            throw new IllegalStateException("Timeline already started");
        }
        this.started = true;
        this.target.resetAll();
        this.sequence.hiddenNodes(0).forEach(nodeId -> this.target.setVisible(nodeId, false));
        startReadySegment();
    }

    @Override
    public void startEvents() {
        if (this.eventsStarted) {
            throw new IllegalStateException("Events already started");
        }
        this.eventsStarted = true;
        if (this.activeAnimation != null && !this.transitioning) {
            this.activeAnimation.startEvents();
        }
    }

    @Override
    public AdvanceResult advance() {
        if (!this.started) {
            throw new IllegalStateException("Timeline has not started");
        }
        if (this.finished) {
            return AdvanceResult.FINISHED;
        }
        if (this.currentTick < Integer.MAX_VALUE) {
            this.currentTick++;
        }

        if (this.transitioning) {
            if (this.currentTick >= this.activeSegment.startTick()) {
                activateTransitionedSegment();
            }
            return AdvanceResult.CONTINUE;
        }

        if (this.activeAnimation != null) {
            AdvanceResult result = this.activeAnimation.advance();
            if (result != AdvanceResult.FINISHED) {
                return result;
            }
            this.activeAnimation.stop();
            this.activeAnimation = null;
            this.activeTarget = null;
            this.activeSegment = null;
        }

        startReadySegment();
        if (this.activeAnimation == null && this.nextSegmentIndex >= this.segments.size()
            && this.currentTick >= this.sequence.durationTicks()) {
            this.finished = true;
            return AdvanceResult.FINISHED;
        }
        return AdvanceResult.CONTINUE;
    }

    @Override
    public AdvanceResult advance(boolean continueAfterLoopBoundary) {
        return advance();
    }

    @Override
    public void deferInitialVisibility() {
        if (!this.started) {
            throw new IllegalStateException("Timeline has not started");
        }
        this.initialVisibilityDeferred = true;
        if (this.activeTarget != null) {
            this.activeTarget.setGloballyDeferred(true);
        }
        this.sequence.animation().nodes().keySet().forEach(nodeId -> this.target.setVisible(nodeId, false));
    }

    @Override
    public void restoreDeferredVisibility() {
        if (!this.initialVisibilityDeferred) {
            return;
        }
        this.initialVisibilityDeferred = false;
        if (this.activeTarget != null) {
            this.activeTarget.setGloballyDeferred(false);
        } else {
            this.sequence.animation().nodes().keySet().forEach(nodeId -> this.target.setVisible(nodeId, false));
        }
    }

    @Override
    public int currentTick() {
        return this.currentTick;
    }

    @Override
    public Identifier emoteId() {
        return this.sequence.animation().id();
    }

    @Override
    public float rotationDeadzone() {
        return this.activeAnimation == null
            ? this.sequence.animation().settings().rotationDeadzone()
            : this.activeAnimation.rotationDeadzone();
    }

    @Override
    public void stop() {
        if (this.activeAnimation != null) {
            this.activeAnimation.stop();
        }
    }

    @Override
    public Transformation currentTransformation(String nodeId) {
        if (this.activeAnimation != null && this.activeSegment.animation().animation().nodes().containsKey(nodeId)) {
            return this.activeAnimation.currentTransformation(nodeId);
        }
        return this.target.createTransformation(nodeId, this.sequence.defaultTransform(nodeId));
    }

    private void startReadySegment() {
        if (this.activeAnimation != null || this.nextSegmentIndex >= this.segments.size()) {
            return;
        }
        PreparedAnimation.PlaybackSegment segment = this.segments.get(this.nextSegmentIndex);
        if (this.currentTick < segment.transitionStartTick()) {
            return;
        }

        this.nextSegmentIndex++;
        this.activeSegment = segment;
        this.transitioning = segment.startTick() > this.currentTick;
        this.activeTarget = new SequenceTimelineTarget(
            this.target,
            segment.mirroredNodes(),
            this.sequence.hiddenNodes(segment.startTick()),
            this.transitioning ? segment.startTick() - this.currentTick : 0
        );
        this.activeTarget.setGloballyDeferred(this.initialVisibilityDeferred);
        this.activeAnimation = new AnimationPlayer(segment.animation(), this.activeTarget, this.querySource);
        if (this.eventExecutor != null) {
            this.activeAnimation.bindEvents(this.eventExecutor);
        }
        if (segment.animation().loopMode() == EmoteAnimation.LoopMode.SERVER_SYNC) {
            this.activeAnimation.startSynchronized(this.serverTick.getAsLong());
        } else {
            this.activeAnimation.start();
        }
        if (!this.transitioning) {
            activateSegmentEvents();
        }
    }

    private void activateTransitionedSegment() {
        this.transitioning = false;
        this.activeTarget.finishTransition();
        activateSegmentEvents();
    }

    private void activateSegmentEvents() {
        if (this.eventsStarted) {
            this.activeAnimation.startEvents();
        }
    }

    private static final class SequenceTimelineTarget implements TimelineTarget {
        private final TimelineTarget delegate;
        private final Map<String, String> mirroredNodes;
        private final Set<String> hiddenNodes;
        private final int transitionTicks;
        private final Map<String, Boolean> visibility = new HashMap<>();
        private final Map<String, CompoundTag> deferredNbt = new HashMap<>();
        private boolean transitioning;
        private boolean globallyDeferred;

        private SequenceTimelineTarget(
            TimelineTarget delegate,
            Map<String, String> mirroredNodes,
            Set<String> hiddenNodes,
            int transitionTicks
        ) {
            this.delegate = delegate;
            this.mirroredNodes = mirroredNodes;
            this.hiddenNodes = hiddenNodes;
            this.transitionTicks = transitionTicks;
            this.transitioning = transitionTicks > 0;
        }

        @Override
        public Transformation createTransformation(String nodeId, PreparedAnimation.PreparedTransform transform) {
            return this.delegate.createTransformation(nodeId, transform);
        }

        @Override
        public Transformation createTransformation(String nodeId, Matrix4fc matrix, boolean preserveMatrix) {
            return this.delegate.createTransformation(nodeId, matrix, preserveMatrix);
        }

        @Override
        public void applyTransform(String nodeId, PreparedAnimation.PreparedTransform transform, int interpolationDurationTicks) {
            int duration = this.transitioning ? this.transitionTicks : interpolationDurationTicks;
            this.delegate.applyTransform(nodeId, transform, duration);
            String mirror = this.mirroredNodes.get(nodeId);
            if (mirror != null) {
                this.delegate.applyTransform(mirror, transform, duration);
            }
        }

        @Override
        public void applyTransform(String nodeId, Matrix4fc matrix, boolean preserveMatrix, int interpolationDurationTicks) {
            int duration = this.transitioning ? this.transitionTicks : interpolationDurationTicks;
            this.delegate.applyTransform(nodeId, matrix, preserveMatrix, duration);
            String mirror = this.mirroredNodes.get(nodeId);
            if (mirror != null) {
                this.delegate.applyTransform(mirror, matrix, preserveMatrix, duration);
            }
        }

        @Override
        public void setVisible(String nodeId, boolean visible) {
            applyVisibility(nodeId, visible);
            String mirror = this.mirroredNodes.get(nodeId);
            if (mirror != null) {
                applyVisibility(mirror, visible);
            }
        }

        @Override
        public void applyNbt(String nodeId, CompoundTag nbt) {
            applyNbtToNode(nodeId, nbt);
            String mirror = this.mirroredNodes.get(nodeId);
            if (mirror != null) {
                applyNbtToNode(mirror, nbt);
            }
        }

        @Override
        public void resetAll() {
            if (this.transitioning) {
                return;
            }
            this.delegate.resetAll();
            this.hiddenNodes.forEach(nodeId -> applyVisibility(nodeId, false));
        }

        private void setGloballyDeferred(boolean deferred) {
            this.globallyDeferred = deferred;
            if (!deferred && !this.transitioning) {
                flushVisibility();
            }
        }

        private void finishTransition() {
            this.transitioning = false;
            this.hiddenNodes.forEach(nodeId -> applyVisibility(nodeId, false));
            if (!this.globallyDeferred) {
                flushVisibility();
            }
            this.deferredNbt.forEach(this.delegate::applyNbt);
            this.deferredNbt.clear();
        }

        private void applyVisibility(String nodeId, boolean visible) {
            this.visibility.put(nodeId, visible);
            if (!this.transitioning && !this.globallyDeferred) {
                this.delegate.setVisible(nodeId, visible);
            }
        }

        private void flushVisibility() {
            this.visibility.forEach(this.delegate::setVisible);
        }

        private void applyNbtToNode(String nodeId, CompoundTag nbt) {
            if (this.transitioning) {
                this.deferredNbt.put(nodeId, nbt.copy());
            } else {
                this.delegate.applyNbt(nodeId, nbt);
            }
        }
    }
}
