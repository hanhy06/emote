package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;

import java.util.*;

public final class PlaybackPlan {
    private final EmoteAnimation animation;
    private final Map<Integer, List<TransformActivation>> transformActivations;
    private final Map<Integer, List<StateActivation>> stateActivations;
    private final Map<String, List<TransformActivation>> nodeTransformActivations;
    private final Map<String, List<StateActivation>> nodeStateActivations;
    private final Map<Integer, List<EmoteAnimation.Event>> timelineEvents;

    private PlaybackPlan(
        EmoteAnimation animation,
        Map<Integer, List<TransformActivation>> transformActivations,
        Map<Integer, List<StateActivation>> stateActivations,
        Map<String, List<TransformActivation>> nodeTransformActivations,
        Map<String, List<StateActivation>> nodeStateActivations,
        Map<Integer, List<EmoteAnimation.Event>> timelineEvents
    ) {
        this.animation = animation;
        this.transformActivations = transformActivations;
        this.stateActivations = stateActivations;
        this.nodeTransformActivations = nodeTransformActivations;
        this.nodeStateActivations = nodeStateActivations;
        this.timelineEvents = timelineEvents;
    }

    public static PlaybackPlan compile(EmoteAnimation animation) {
        Objects.requireNonNull(animation, "animation");
        Map<Integer, List<TransformActivation>> transformsByTick = new HashMap<>();
        Map<Integer, List<StateActivation>> statesByTick = new HashMap<>();
        Map<String, List<TransformActivation>> transformsByNode = new HashMap<>();
        Map<String, List<StateActivation>> statesByNode = new HashMap<>();
        Map<String, EmoteAnimation.Matrix> previousMatrices = new HashMap<>();
        Set<String> transformedNodes = new HashSet<>();

        animation.nodes().forEach((nodeId, node) -> previousMatrices.put(nodeId, node.defaultMatrix()));
        for (EmoteAnimation.Keyframe keyframe : animation.timeline().keyframes()) {
            for (Map.Entry<String, EmoteAnimation.NodeTransform> entry : keyframe.nodeTransforms().entrySet()) {
                String nodeId = entry.getKey();
                EmoteAnimation.NodeTransform transform = entry.getValue();
                if (transformedNodes.contains(nodeId) && transform.matrix().equals(previousMatrices.get(nodeId))) {
                    continue;
                }
                int activationTick = keyframe.tick() - transform.interpolationDurationTicks();
                TransformActivation activation = new TransformActivation(
                    activationTick,
                    keyframe.tick(),
                    nodeId,
                    previousMatrices.get(nodeId),
                    transform
                );
                transformsByTick.computeIfAbsent(activationTick, ignored -> new ArrayList<>()).add(activation);
                transformsByNode.computeIfAbsent(nodeId, ignored -> new ArrayList<>()).add(activation);
                previousMatrices.put(nodeId, transform.matrix());
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

        return new PlaybackPlan(
            animation,
            copyListMap(transformsByTick),
            copyListMap(statesByTick),
            copyListMap(transformsByNode),
            copyListMap(statesByNode),
            indexTimelineEvents(animation.timeline().events().timeline())
        );
    }

    EmoteAnimation animation() {
        return this.animation;
    }

    List<TransformActivation> transformActivations(int tick) {
        return this.transformActivations.getOrDefault(tick, List.of());
    }

    List<StateActivation> stateActivations(int tick) {
        return this.stateActivations.getOrDefault(tick, List.of());
    }

    List<EmoteAnimation.Event> timelineEvents(int tick) {
        return this.timelineEvents.getOrDefault(tick, List.of());
    }

    TransformActivation activeTransform(String nodeId, int tick) {
        return findLastAtOrBefore(
            this.nodeTransformActivations.getOrDefault(nodeId, List.of()),
            tick,
            TransformActivation::activationTick
        );
    }

    boolean visible(String nodeId, int tick, boolean defaultValue) {
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

    record TransformActivation(
        int activationTick,
        int targetTick,
        String nodeId,
        EmoteAnimation.Matrix previousMatrix,
        EmoteAnimation.NodeTransform transform
    ) {
    }

    record StateActivation(int tick, String nodeId, EmoteAnimation.NodeState state) {
    }
}
