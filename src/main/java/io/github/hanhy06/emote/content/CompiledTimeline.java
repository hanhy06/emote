package io.github.hanhy06.emote.content;

import com.mojang.math.Transformation;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

public final class CompiledTimeline {
    private final EmoteAnimation animation;
    private final Map<Integer, List<TransformActivation>> transformActivations;
    private final Map<Integer, List<StateActivation>> stateActivations;
    private final Map<String, List<TransformActivation>> nodeTransformActivations;
    private final Map<String, List<StateActivation>> nodeStateActivations;
    private final Map<Integer, List<EmoteAnimation.Event>> timelineEvents;
    private final Map<String, PreparedTransform> defaultTransforms;

    private CompiledTimeline(
        EmoteAnimation animation,
        Map<Integer, List<TransformActivation>> transformActivations,
        Map<Integer, List<StateActivation>> stateActivations,
        Map<String, List<TransformActivation>> nodeTransformActivations,
        Map<String, List<StateActivation>> nodeStateActivations,
        Map<Integer, List<EmoteAnimation.Event>> timelineEvents,
        Map<String, PreparedTransform> defaultTransforms
    ) {
        this.animation = animation;
        this.transformActivations = transformActivations;
        this.stateActivations = stateActivations;
        this.nodeTransformActivations = nodeTransformActivations;
        this.nodeStateActivations = nodeStateActivations;
        this.timelineEvents = timelineEvents;
        this.defaultTransforms = defaultTransforms;
    }

    public static CompiledTimeline compile(EmoteAnimation animation) {
        Objects.requireNonNull(animation, "animation");
        Map<Integer, List<TransformActivation>> transformsByTick = new HashMap<>();
        Map<Integer, List<StateActivation>> statesByTick = new HashMap<>();
        Map<String, List<TransformActivation>> transformsByNode = new HashMap<>();
        Map<String, List<StateActivation>> statesByNode = new HashMap<>();
        Map<String, PreparedTransform> defaultTransforms = new HashMap<>();
        Set<String> transformedNodes = new HashSet<>();

        animation.nodes().forEach((nodeId, node) -> defaultTransforms.put(
            nodeId,
            PreparedTransform.create(node.defaultMatrix(), node instanceof EmoteAnimation.AnchorNode)
        ));
        Map<String, PreparedTransform> previousTransforms = new HashMap<>(defaultTransforms);
        for (EmoteAnimation.Keyframe keyframe : animation.timeline().keyframes()) {
            for (Map.Entry<String, EmoteAnimation.NodeTransform> entry : keyframe.nodeTransforms().entrySet()) {
                String nodeId = entry.getKey();
                EmoteAnimation.NodeTransform transform = entry.getValue();
                PreparedTransform previousTransform = previousTransforms.get(nodeId);
                if (transformedNodes.contains(nodeId) && transform.matrix().equals(previousTransform.matrix())) {
                    continue;
                }
                PreparedTransform preparedTransform = PreparedTransform.create(
                    transform.matrix(),
                    animation.nodes().get(nodeId) instanceof EmoteAnimation.AnchorNode
                );
                int activationTick = keyframe.tick() - transform.interpolationDurationTicks();
                TransformActivation activation = new TransformActivation(
                    activationTick,
                    keyframe.tick(),
                    nodeId,
                    previousTransform,
                    preparedTransform,
                    transform.interpolationDurationTicks()
                );
                transformsByTick.computeIfAbsent(activationTick, ignored -> new ArrayList<>()).add(activation);
                transformsByNode.computeIfAbsent(nodeId, ignored -> new ArrayList<>()).add(activation);
                previousTransforms.put(nodeId, preparedTransform);
                transformedNodes.add(nodeId);
            }
            for (Map.Entry<String, EmoteAnimation.NodeState> entry : keyframe.nodeStates().entrySet()) {
                StateActivation activation = new StateActivation(keyframe.tick(), entry.getKey(), entry.getValue());
                statesByTick.computeIfAbsent(keyframe.tick(), ignored -> new ArrayList<>()).add(activation);
                statesByNode.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).add(activation);
            }
        }

        Comparator<TransformActivation> transformOrder = Comparator
            .comparingInt(TransformActivation::activationTick)
            .thenComparingInt(TransformActivation::targetTick);
        transformsByTick.values().forEach(values -> values.sort(transformOrder));
        transformsByNode.values().forEach(values -> values.sort(transformOrder));
        statesByNode.values().forEach(values -> values.sort(Comparator.comparingInt(StateActivation::tick)));

        return new CompiledTimeline(
            animation,
            copyListMap(transformsByTick),
            copyListMap(statesByTick),
            copyListMap(transformsByNode),
            copyListMap(statesByNode),
            indexTimelineEvents(animation.timeline().events().timeline()),
            Map.copyOf(defaultTransforms)
        );
    }

    public EmoteAnimation animation() {
        return this.animation;
    }

    public List<TransformActivation> transformActivations(int tick) {
        return this.transformActivations.getOrDefault(tick, List.of());
    }

    public List<StateActivation> stateActivations(int tick) {
        return this.stateActivations.getOrDefault(tick, List.of());
    }

    public List<EmoteAnimation.Event> timelineEvents(int tick) {
        return this.timelineEvents.getOrDefault(tick, List.of());
    }

    public PreparedTransform defaultTransform(String nodeId) {
        PreparedTransform transform = this.defaultTransforms.get(nodeId);
        if (transform == null) {
            throw new IllegalStateException("Missing default transform for node: " + nodeId);
        }
        return transform;
    }

    public TransformActivation activeTransform(String nodeId, int tick) {
        return findLastAtOrBefore(
            this.nodeTransformActivations.getOrDefault(nodeId, List.of()),
            tick,
            TransformActivation::activationTick
        );
    }

    public boolean visible(String nodeId, int tick, boolean defaultValue) {
        StateActivation activation = findLastAtOrBefore(
            this.nodeStateActivations.getOrDefault(nodeId, List.of()),
            tick,
            StateActivation::tick
        );
        return activation == null ? defaultValue : activation.state().visible();
    }

    private static Map<Integer, List<EmoteAnimation.Event>> indexTimelineEvents(
        List<EmoteAnimation.TimelineEvent> events
    ) {
        Map<Integer, List<EmoteAnimation.Event>> byTick = new LinkedHashMap<>();
        for (EmoteAnimation.TimelineEvent event : events) {
            byTick.computeIfAbsent(event.tick(), ignored -> new ArrayList<>()).add(event.event());
        }
        return copyListMap(byTick);
    }

    private static <K, V> Map<K, List<V>> copyListMap(Map<K, List<V>> source) {
        Map<K, List<V>> copied = new HashMap<>();
        source.forEach((key, values) -> copied.put(key, List.copyOf(values)));
        return Map.copyOf(copied);
    }

    private static <T> T findLastAtOrBefore(
        List<T> values,
        int tick,
        java.util.function.ToIntFunction<T> tickExtractor
    ) {
        int low = 0;
        int high = values.size() - 1;
        T result = null;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            T candidate = values.get(middle);
            if (tickExtractor.applyAsInt(candidate) <= tick) {
                result = candidate;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return result;
    }

    public record TransformActivation(
        int activationTick,
        int targetTick,
        String nodeId,
        PreparedTransform previousTransform,
        PreparedTransform transform,
        int interpolationDurationTicks
    ) {
    }

    public record StateActivation(int tick, String nodeId, EmoteAnimation.NodeState state) {
    }

    public static final class PreparedTransform {
        private final EmoteAnimation.Matrix matrix;
        private final Matrix4f localMatrix;
        private final Vector3f translation;
        private final Quaternionf leftRotation;
        private final Vector3f scale;
        private final Quaternionf rightRotation;

        private PreparedTransform(
            EmoteAnimation.Matrix matrix,
            Matrix4f localMatrix,
            Vector3f translation,
            Quaternionf leftRotation,
            Vector3f scale,
            Quaternionf rightRotation
        ) {
            this.matrix = matrix;
            this.localMatrix = localMatrix;
            this.translation = translation;
            this.leftRotation = leftRotation;
            this.scale = scale;
            this.rightRotation = rightRotation;
        }

        public static PreparedTransform create(EmoteAnimation.Matrix matrix, boolean preserveMatrix) {
            Matrix4f localMatrix = toJoml(matrix);
            if (preserveMatrix) {
                return new PreparedTransform(matrix, localMatrix, null, null, null, null);
            }
            Transformation transformation = new Transformation(localMatrix);
            return new PreparedTransform(
                matrix,
                localMatrix,
                new Vector3f(transformation.translation()),
                new Quaternionf(transformation.leftRotation()),
                new Vector3f(transformation.scale()),
                new Quaternionf(transformation.rightRotation())
            );
        }

        private static Matrix4f toJoml(EmoteAnimation.Matrix matrix) {
            return new Matrix4f(
                (float) matrix.value(0), (float) matrix.value(4), (float) matrix.value(8), (float) matrix.value(12),
                (float) matrix.value(1), (float) matrix.value(5), (float) matrix.value(9), (float) matrix.value(13),
                (float) matrix.value(2), (float) matrix.value(6), (float) matrix.value(10), (float) matrix.value(14),
                (float) matrix.value(3), (float) matrix.value(7), (float) matrix.value(11), (float) matrix.value(15)
            );
        }

        public EmoteAnimation.Matrix matrix() {
            return this.matrix;
        }

        public boolean preservesMatrix() {
            return this.translation == null;
        }

        public Matrix4f localMatrix() {
            return this.localMatrix;
        }

        public Vector3f translation() {
            return this.translation;
        }

        public Quaternionf leftRotation() {
            return this.leftRotation;
        }

        public Vector3f scale() {
            return this.scale;
        }

        public Quaternionf rightRotation() {
            return this.rightRotation;
        }
    }
}
