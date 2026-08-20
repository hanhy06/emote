package io.github.hanhy06.emote.content;

import com.mojang.math.Transformation;
import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.ParticipantRole;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.skin.SkinBinding;
import io.github.hanhy06.emote.skin.SkinBindingCompiler;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.nio.file.Path;
import java.util.*;

public final class PreparedAnimation implements PlayableEmote {
    private static final SkinBindingCompiler SKIN_PART_FACTORY = new SkinBindingCompiler();
    private final LoadedAnimation source;
    private final List<SkinBinding> skinParts;
    private final EmoteAnimation animation;
    private final PreparedAnimationTimeline preparedTimeline;
    private final Map<Integer, List<EmoteAnimation.Event>> timelineEvents;
    private final Map<String, PreparedTransform> defaultTransforms;
    private final int displayNodeCount;
    private final List<PlaybackSegment> playbackSegments;
    private final Map<Integer, Set<String>> hiddenNodes;

    private PreparedAnimation(
        LoadedAnimation source,
        List<SkinBinding> skinParts,
        EmoteAnimation animation,
        PreparedAnimationTimeline preparedTimeline,
        Map<Integer, List<EmoteAnimation.Event>> timelineEvents,
        Map<String, PreparedTransform> defaultTransforms,
        int displayNodeCount,
        List<PlaybackSegment> playbackSegments,
        Map<Integer, Set<String>> hiddenNodes
    ) {
        this.source = source;
        this.skinParts = skinParts;
        this.animation = animation;
        this.preparedTimeline = preparedTimeline;
        this.timelineEvents = timelineEvents;
        this.defaultTransforms = defaultTransforms;
        this.displayNodeCount = displayNodeCount;
        this.playbackSegments = playbackSegments;
        this.hiddenNodes = hiddenNodes;
    }

    public static PreparedAnimation from(LoadedAnimation source) {
        return from(source, SKIN_PART_FACTORY.create(source.animation()));
    }

    public static PreparedAnimation from(LoadedAnimation source, List<SkinBinding> skinParts) {
        Objects.requireNonNull(source, "source");
        skinParts = List.copyOf(skinParts);
        EmoteAnimation animation = source.animation();
        Objects.requireNonNull(animation, "animation");
        Map<String, PreparedTransform> defaultTransforms = new HashMap<>();

        animation.nodes().forEach((nodeId, node) -> defaultTransforms.put(
            nodeId,
            PreparedTransform.create(node.transform(), node instanceof EmoteAnimation.AnchorNode)
        ));
        Map<Integer, List<EmoteAnimation.Event>> eventsByTick = new HashMap<>();
        for (EmoteAnimation.TimelineEvent event : animation.timeline().events().timeline()) {
            eventsByTick.computeIfAbsent(event.tick(), ignored -> new ArrayList<>()).add(event.event());
        }

        return new PreparedAnimation(
            source,
            skinParts,
            animation,
            PreparedAnimationTimeline.compile(animation),
            copyListMap(eventsByTick),
            Map.copyOf(defaultTransforms),
            (int) animation.nodes().values().stream().filter(node -> !(node instanceof EmoteAnimation.AnchorNode)).count(),
            List.of(),
            Map.of()
        );
    }

    static PreparedAnimation sequence(
        PreparedAnimation layout,
        List<PlaybackSegment> playbackSegments,
        Map<Integer, Set<String>> hiddenNodes
    ) {
        Objects.requireNonNull(layout, "layout");
        Map<Integer, Set<String>> copiedHiddenNodes = new HashMap<>();
        hiddenNodes.forEach((tick, nodeIds) -> copiedHiddenNodes.put(tick, Set.copyOf(nodeIds)));
        return new PreparedAnimation(
            layout.source,
            layout.skinParts,
            layout.animation,
            layout.preparedTimeline,
            layout.timelineEvents,
            layout.defaultTransforms,
            layout.displayNodeCount,
            List.copyOf(playbackSegments),
            Map.copyOf(copiedHiddenNodes)
        );
    }

    public LoadedAnimation source() {
        return this.source;
    }

    public EmoteAnimation animation() {
        return this.animation;
    }

    public PreparedAnimationTimeline preparedTimeline() {
        return this.preparedTimeline;
    }

    public List<SkinBinding> skinParts() {
        return this.skinParts;
    }

    public String id() {
        return animation().id().toString();
    }

    public EmoteMetadata metadata() {
        return animation().metadata();
    }

    @Override
    public boolean standalone() {
        return animation().settings().standalone();
    }

    public EmotePlayerBehavior playerBehavior() {
        return animation().settings().player();
    }

    public Path sourcePath() {
        return this.source.sourcePath();
    }

    public int nodeCount() {
        return animation().nodes().size();
    }

    @Override
    public int durationTicks() {
        return animation().timeline().durationTicks();
    }

    @Override
    public int cooldownTicks() {
        return animation().settings().cooldownTicks();
    }

    @Override
    public EmoteAnimation.LoopMode loopMode() {
        return animation().settings().playback().mode();
    }

    public List<SkinBinding> skinParts(ParticipantRole participant) {
        return this.skinParts.stream().filter(binding -> binding.participant() == participant).toList();
    }

    public List<EmoteAnimation.Event> timelineEvents(int tick) {
        return this.timelineEvents.getOrDefault(tick, List.of());
    }

    public int displayNodeCount() {
        return this.displayNodeCount;
    }

    public List<PlaybackSegment> playbackSegments() {
        return this.playbackSegments;
    }

    public Set<String> hiddenNodes(int tick) {
        return this.hiddenNodes.getOrDefault(tick, Set.of());
    }

    public PreparedTransform defaultTransform(String nodeId) {
        PreparedTransform transform = this.defaultTransforms.get(nodeId);
        if (transform == null) {
            throw new IllegalStateException("Missing default transform for node: " + nodeId);
        }
        return transform;
    }

    private static <K, V> Map<K, List<V>> copyListMap(Map<K, List<V>> source) {
        Map<K, List<V>> copied = new HashMap<>();
        source.forEach((key, values) -> copied.put(key, List.copyOf(values)));
        return Map.copyOf(copied);
    }

    public record PlaybackSegment(
        int startTick,
        int endTick,
        PreparedAnimation animation,
        Map<String, String> mirroredNodes
    ) {
        public PlaybackSegment {
            if (startTick < 0 || endTick < startTick) {
                throw new IllegalArgumentException("invalid playback segment range");
            }
            Objects.requireNonNull(animation, "animation");
            mirroredNodes = Map.copyOf(mirroredNodes);
        }
    }

    public static final class PreparedTransform {
        private final Matrix4f localMatrix;
        private final Vector3f translation;
        private final Quaternionf leftRotation;
        private final Vector3f scale;
        private final Quaternionf rightRotation;

        private PreparedTransform(
            Matrix4f localMatrix,
            Vector3f translation,
            Quaternionf leftRotation,
            Vector3f scale,
            Quaternionf rightRotation
        ) {
            this.localMatrix = localMatrix;
            this.translation = translation;
            this.leftRotation = leftRotation;
            this.scale = scale;
            this.rightRotation = rightRotation;
        }

        public static PreparedTransform create(EmoteAnimation.LocalTransform transform, boolean preserveMatrix) {
            EmoteAnimation.Vec3 position = transform.position();
            EmoteAnimation.Vec3 rotation = transform.rotation();
            EmoteAnimation.Vec3 scale = transform.scale();
            Matrix4f matrix = new Matrix4f()
                .translate((float) position.x(), (float) position.y(), (float) position.z())
                .rotate(new Quaternionf().rotationXYZ(
                    (float) Math.toRadians(rotation.x()),
                    (float) Math.toRadians(rotation.y()),
                    (float) Math.toRadians(rotation.z())
                ))
                .scale((float) scale.x(), (float) scale.y(), (float) scale.z());
            return create(matrix, preserveMatrix);
        }

        public static PreparedTransform create(Matrix4f matrix, boolean preserveMatrix) {
            Matrix4f localMatrix = new Matrix4f(matrix);
            if (preserveMatrix) {
                return new PreparedTransform(localMatrix, null, null, null, null);
            }
            Transformation transformation = new Transformation(localMatrix);
            return new PreparedTransform(
                localMatrix,
                new Vector3f(transformation.translation()),
                new Quaternionf(transformation.leftRotation()),
                new Vector3f(transformation.scale()),
                new Quaternionf(transformation.rightRotation())
            );
        }

        public boolean hasSameMatrix(PreparedTransform other) {
            return this.localMatrix.equals(other.localMatrix);
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
