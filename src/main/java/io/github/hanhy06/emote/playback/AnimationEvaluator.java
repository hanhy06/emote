package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.playback.molang.MolangEngine;
import io.github.hanhy06.emote.playback.molang.MolangQueries;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.content.PreparedAnimationTimeline;
import io.github.hanhy06.emote.content.PreparedAnimation;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.github.hanhy06.emote.api.animation.EmoteAnimation.*;
import static io.github.hanhy06.emote.content.PreparedAnimationTimeline.*;

final class AnimationEvaluator {
    private final PreparedAnimation animation;
    private final PreparedAnimationTimeline timeline;
    private final MolangQueries.Source querySource;
    private final NodeState[] nodes;
    private final Map<String, Integer> nodeIndexes;
    private final Matrix4f localMatrix = new Matrix4f();
    private final Quaternionf rotation = new Quaternionf();
    private final Quaternionf endRotation = new Quaternionf();
    private final double[] position = new double[3];
    private final double[] rotationVector = new double[3];
    private final double[] scale = new double[3];
    private final double[] endVector = new double[3];

    private MolangEngine.Session session;

    AnimationEvaluator(PreparedAnimation animation, MolangQueries.Source querySource) {
        this.animation = animation;
        this.timeline = animation.preparedTimeline();
        this.querySource = querySource;
        this.nodes = new NodeState[this.timeline.nodeOrder().size()];
        Map<String, Integer> indexes = new HashMap<>();
        EmoteAnimation source = animation.animation();
        for (int index = 0; index < this.nodes.length; index++) {
            String nodeId = this.timeline.nodeOrder().get(index);
            Node node = source.nodes().get(nodeId);
            Integer parentIndex = node.parentId() == null ? null : indexes.get(node.parentId());
            if (node.parentId() != null && parentIndex == null) {
                throw new IllegalStateException("Parent node was not prepared before child: " + nodeId);
            }
            this.nodes[index] = new NodeState(
                nodeId,
                node,
                this.timeline.tracks().get(nodeId),
                parentIndex == null ? -1 : parentIndex
            );
            indexes.put(nodeId, index);
        }
        this.nodeIndexes = Map.copyOf(indexes);
    }

    void beginCycle(int tick, int loopCount) {
        this.session = MolangEngine.INSTANCE.createSession();
        setQueries(tick, loopCount, 0.0D);
        if (this.timeline.initialize() != null) {
            this.session.evaluate(this.timeline.initialize());
        }
        for (NodeState node : this.nodes) node.resetCursors(tick);
        evaluate(tick, loopCount, 0.0D, this.timeline.tick() != null);
    }

    void evaluate(int tick, int loopCount) {
        evaluate(tick, loopCount, 0.05D, this.timeline.tick() != null);
    }

    int nodeCount() {
        return this.nodes.length;
    }

    String nodeId(int index) {
        return this.nodes[index].id;
    }

    Matrix4fc matrix(int index) {
        return this.nodes[index].worldMatrix;
    }

    Matrix4fc matrix(String nodeId) {
        Integer index = this.nodeIndexes.get(nodeId);
        return index == null ? null : this.nodes[index].worldMatrix;
    }

    boolean preservesMatrix(int index) {
        return this.nodes[index].node instanceof AnchorNode;
    }

    boolean preservesMatrix(String nodeId) {
        Integer index = this.nodeIndexes.get(nodeId);
        return index != null && preservesMatrix(index);
    }

    boolean visible(int index) {
        return this.nodes[index].visible;
    }

    private void evaluate(int tick, int loopCount, double deltaTime, boolean runTick) {
        setQueries(tick, loopCount, deltaTime);
        if (runTick) {
            this.session.evaluate(this.timeline.tick());
        }

        for (NodeState state : this.nodes) {
            CompiledNodeTracks tracks = state.tracks;
            LocalTransform defaults = state.node.transform();
            state.positionCursor = vector(
                tracks == null ? List.of() : tracks.position(),
                state.positionCursor,
                tick,
                defaults.position(),
                this.position
            );
            state.scaleCursor = vector(
                tracks == null ? List.of() : tracks.scale(),
                state.scaleCursor,
                tick,
                defaults.scale(),
                this.scale
            );
            state.rotationCursor = rotation(
                tracks == null ? List.of() : tracks.rotation(),
                state.rotationCursor,
                tick,
                defaults.rotation(),
                this.rotation
            );
            this.localMatrix.identity()
                .translate((float) this.position[0], (float) this.position[1], (float) this.position[2])
                .rotate(this.rotation)
                .scale((float) this.scale[0], (float) this.scale[1], (float) this.scale[2]);
            if (state.parentIndex < 0) {
                state.worldMatrix.set(this.localMatrix);
            } else {
                state.worldMatrix.set(this.nodes[state.parentIndex].worldMatrix).mul(this.localMatrix);
            }
            state.visibilityCursor = visible(state, tick);
        }
    }

    private void setQueries(int tick, int loopCount, double deltaTime) {
        double animationTime = tick / 20.0D;
        this.session.setQuery("anim_time", animationTime);
        this.session.setQuery("anim_time_ticks", tick);
        this.session.setQuery("anim_length", this.animation.durationTicks() / 20.0D);
        this.session.setQuery("delta_time", deltaTime);
        this.session.setQuery("loop_count", loopCount);
        this.session.setQuery("key_frame_lerp_time", 0.0D);
        this.session.setQuery("life_time", animationTime);
        this.querySource.apply(this.session);
    }

    private int vector(List<CompiledVectorKeyframe> frames, int currentIndex, int tick, Vec3 defaults, double[] target) {
        if (frames.isEmpty()) {
            target[0] = defaults.x();
            target[1] = defaults.y();
            target[2] = defaults.z();
            return 0;
        }
        currentIndex = advanceCursor(frames, currentIndex, tick);
        CompiledVectorKeyframe current = frames.get(currentIndex);
        CompiledVectorKeyframe next = currentIndex + 1 < frames.size() ? frames.get(currentIndex + 1) : null;
        double progress = progress(current, next, tick);
        this.session.setQuery("key_frame_lerp_time", progress);
        current.post().evaluate(this.session, target);
        if (next == null || current.interpolation() == Interpolation.STEP) {
            return currentIndex;
        }
        next.pre().evaluate(this.session, this.endVector);
        progress = easing(current.easing(), progress);
        target[0] = lerp(target[0], this.endVector[0], progress);
        target[1] = lerp(target[1], this.endVector[1], progress);
        target[2] = lerp(target[2], this.endVector[2], progress);
        return currentIndex;
    }

    private int rotation(
        List<CompiledVectorKeyframe> frames,
        int currentIndex,
        int tick,
        Vec3 defaults,
        Quaternionf target
    ) {
        if (frames.isEmpty()) {
            target.rotationXYZ(
                (float) Math.toRadians(defaults.x()),
                (float) Math.toRadians(defaults.y()),
                (float) Math.toRadians(defaults.z())
            );
            return 0;
        }
        currentIndex = advanceCursor(frames, currentIndex, tick);
        CompiledVectorKeyframe current = frames.get(currentIndex);
        CompiledVectorKeyframe next = currentIndex + 1 < frames.size() ? frames.get(currentIndex + 1) : null;
        double progress = progress(current, next, tick);
        this.session.setQuery("key_frame_lerp_time", progress);
        current.post().evaluate(this.session, this.rotationVector);
        quaternion(this.rotationVector, target);
        if (next == null || current.interpolation() == Interpolation.STEP) {
            return currentIndex;
        }
        next.pre().evaluate(this.session, this.endVector);
        quaternion(this.endVector, this.endRotation);
        target.slerp(this.endRotation, (float) easing(current.easing(), progress));
        return currentIndex;
    }

    private int visible(NodeState state, int tick) {
        if (state.node instanceof AnchorNode) {
            state.visible = true;
            return 0;
        }
        CompiledNodeTracks tracks = state.tracks;
        if (tracks == null || tracks.visible().isEmpty()) {
            state.visible = state.node.visible();
            return 0;
        }
        int currentIndex = advanceVisibilityCursor(tracks.visible(), state.visibilityCursor, tick);
        CompiledVisibilityKeyframe current = tracks.visible().get(currentIndex);
        this.session.setQuery("key_frame_lerp_time", 0.0D);
        state.visible = current.value().evaluate(this.session);
        return currentIndex;
    }

    private int advanceCursor(List<CompiledVectorKeyframe> frames, int currentIndex, int tick) {
        while (currentIndex + 1 < frames.size() && frames.get(currentIndex + 1).tick() <= tick) {
            currentIndex++;
        }
        return currentIndex;
    }

    private int advanceVisibilityCursor(List<CompiledVisibilityKeyframe> frames, int currentIndex, int tick) {
        while (currentIndex + 1 < frames.size() && frames.get(currentIndex + 1).tick() <= tick) {
            currentIndex++;
        }
        return currentIndex;
    }

    private double progress(CompiledVectorKeyframe current, CompiledVectorKeyframe next, int tick) {
        if (next == null) return 0.0D;
        return Math.clamp((double) (tick - current.tick()) / (next.tick() - current.tick()), 0.0D, 1.0D);
    }

    private void quaternion(double[] degrees, Quaternionf target) {
        target.rotationXYZ(
            (float) Math.toRadians(degrees[0]),
            (float) Math.toRadians(degrees[1]),
            (float) Math.toRadians(degrees[2])
        );
    }

    private double easing(Easing easing, double value) {
        return switch (easing) {
            case LINEAR -> value;
            case EASE_IN_SINE -> in(EasingKind.SINE, value);
            case EASE_OUT_SINE -> out(EasingKind.SINE, value);
            case EASE_IN_OUT_SINE -> inOut(EasingKind.SINE, value);
            case EASE_IN_QUAD -> in(EasingKind.QUAD, value);
            case EASE_OUT_QUAD -> out(EasingKind.QUAD, value);
            case EASE_IN_OUT_QUAD -> inOut(EasingKind.QUAD, value);
            case EASE_IN_CUBIC -> in(EasingKind.CUBIC, value);
            case EASE_OUT_CUBIC -> out(EasingKind.CUBIC, value);
            case EASE_IN_OUT_CUBIC -> inOut(EasingKind.CUBIC, value);
            case EASE_IN_QUART -> in(EasingKind.QUART, value);
            case EASE_OUT_QUART -> out(EasingKind.QUART, value);
            case EASE_IN_OUT_QUART -> inOut(EasingKind.QUART, value);
            case EASE_IN_QUINT -> in(EasingKind.QUINT, value);
            case EASE_OUT_QUINT -> out(EasingKind.QUINT, value);
            case EASE_IN_OUT_QUINT -> inOut(EasingKind.QUINT, value);
            case EASE_IN_EXPO -> in(EasingKind.EXPO, value);
            case EASE_OUT_EXPO -> out(EasingKind.EXPO, value);
            case EASE_IN_OUT_EXPO -> inOut(EasingKind.EXPO, value);
            case EASE_IN_CIRC -> in(EasingKind.CIRC, value);
            case EASE_OUT_CIRC -> out(EasingKind.CIRC, value);
            case EASE_IN_OUT_CIRC -> inOut(EasingKind.CIRC, value);
            case EASE_IN_BACK -> in(EasingKind.BACK, value);
            case EASE_OUT_BACK -> out(EasingKind.BACK, value);
            case EASE_IN_OUT_BACK -> inOut(EasingKind.BACK, value);
            case EASE_IN_ELASTIC -> in(EasingKind.ELASTIC, value);
            case EASE_OUT_ELASTIC -> out(EasingKind.ELASTIC, value);
            case EASE_IN_OUT_ELASTIC -> inOut(EasingKind.ELASTIC, value);
            case EASE_IN_BOUNCE -> in(EasingKind.BOUNCE, value);
            case EASE_OUT_BOUNCE -> out(EasingKind.BOUNCE, value);
            case EASE_IN_OUT_BOUNCE -> inOut(EasingKind.BOUNCE, value);
        };
    }

    private double in(EasingKind kind, double value) {
        return switch (kind) {
            case SINE -> 1.0D - Math.cos(Math.PI * value / 2.0D);
            case QUAD -> value * value;
            case CUBIC -> value * value * value;
            case QUART -> Math.pow(value, 4.0D);
            case QUINT -> Math.pow(value, 5.0D);
            case EXPO -> value == 0.0D ? 0.0D : Math.pow(2.0D, 10.0D * (value - 1.0D));
            case CIRC -> 1.0D - Math.sqrt(1.0D - value * value);
            case BACK -> value * value * ((1.70158D + 1.0D) * value - 1.70158D);
            case ELASTIC -> 1.0D - Math.pow(Math.cos(Math.PI * value / 2.0D), 3.0D) * Math.cos(Math.PI * value);
            case BOUNCE -> Math.min(
                Math.min(121.0D / 16.0D * value * value, 121.0D / 8.0D * Math.pow(value - 6.0D / 11.0D, 2.0D) + 0.5D),
                Math.min(
                    121.0D / 4.0D * Math.pow(value - 9.0D / 11.0D, 2.0D) + 0.75D,
                    121.0D / 2.0D * Math.pow(value - 10.5D / 11.0D, 2.0D) + 0.875D
                )
            );
        };
    }

    private double out(EasingKind kind, double value) {
        return 1.0D - in(kind, 1.0D - value);
    }

    private double inOut(EasingKind kind, double value) {
        return value < 0.5D ? in(kind, value * 2.0D) / 2.0D : 1.0D - in(kind, (1.0D - value) * 2.0D) / 2.0D;
    }

    private double lerp(double start, double end, double progress) {
        return start + (end - start) * progress;
    }

    private static int findVectorCursor(List<CompiledVectorKeyframe> frames, int tick) {
        int low = 1;
        int high = frames.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (frames.get(middle).tick() <= tick) low = middle + 1;
            else high = middle;
        }
        return Math.max(0, low - 1);
    }

    private static int findVisibilityCursor(List<CompiledVisibilityKeyframe> frames, int tick) {
        int low = 1;
        int high = frames.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (frames.get(middle).tick() <= tick) low = middle + 1;
            else high = middle;
        }
        return Math.max(0, low - 1);
    }

    private static final class NodeState {
        private final String id;
        private final Node node;
        private final CompiledNodeTracks tracks;
        private final int parentIndex;
        private final Matrix4f worldMatrix = new Matrix4f();

        private int positionCursor;
        private int rotationCursor;
        private int scaleCursor;
        private int visibilityCursor;
        private boolean visible;

        private NodeState(String id, Node node, CompiledNodeTracks tracks, int parentIndex) {
            this.id = id;
            this.node = node;
            this.tracks = tracks;
            this.parentIndex = parentIndex;
        }

        private void resetCursors(int tick) {
            this.positionCursor = this.tracks == null || this.tracks.position().isEmpty()
                ? 0 : findVectorCursor(this.tracks.position(), tick);
            this.rotationCursor = this.tracks == null || this.tracks.rotation().isEmpty()
                ? 0 : findVectorCursor(this.tracks.rotation(), tick);
            this.scaleCursor = this.tracks == null || this.tracks.scale().isEmpty()
                ? 0 : findVectorCursor(this.tracks.scale(), tick);
            this.visibilityCursor = this.tracks == null || this.tracks.visible().isEmpty()
                ? 0 : findVisibilityCursor(this.tracks.visible(), tick);
        }
    }

    private enum EasingKind {
        SINE,
        QUAD,
        CUBIC,
        QUART,
        QUINT,
        EXPO,
        CIRC,
        BACK,
        ELASTIC,
        BOUNCE
    }
}
