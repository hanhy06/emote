package io.github.hanhy06.emote.content;

import net.minecraft.world.phys.Vec3;
import com.mojang.math.Transformation;
import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.PlaybackTimeline;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.skin.SkinBinding;
import io.github.hanhy06.emote.skin.SkinBindingCompiler;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.nio.file.Path;
import java.util.*;

public final class PreparedEmote implements PlayableEmote {
    private static final SkinBindingCompiler SKIN_BINDING_COMPILER = new SkinBindingCompiler();
    private final LoadedAnimation source;
    private final List<SkinBinding> skinBindings;
    private final EmoteAnimation model;
    private final PreparedAnimationTimeline preparedTimeline;
    private final Map<Integer, List<PreparedEvent>> timelineEvents;
    private final Map<String, PreparedTransform> defaultTransforms;
    private final int displayNodeCount;
    private final List<PlaybackSegment> playbackSegments;
    private final Map<Integer, Set<String>> hiddenNodes;
    private final PlaybackTimeline playbackTimeline;

    private PreparedEmote(
        LoadedAnimation source,
        List<SkinBinding> skinBindings,
        EmoteAnimation model,
        PreparedAnimationTimeline preparedTimeline,
        Map<Integer, List<PreparedEvent>> timelineEvents,
        Map<String, PreparedTransform> defaultTransforms,
        int displayNodeCount,
        List<PlaybackSegment> playbackSegments,
        Map<Integer, Set<String>> hiddenNodes,
        PlaybackTimeline playbackTimeline
    ) {
        this.source = source;
        this.skinBindings = skinBindings;
        this.model = model;
        this.preparedTimeline = preparedTimeline;
        this.timelineEvents = timelineEvents;
        this.defaultTransforms = defaultTransforms;
        this.displayNodeCount = displayNodeCount;
        this.playbackSegments = playbackSegments;
        this.hiddenNodes = hiddenNodes;
        this.playbackTimeline = playbackTimeline;
    }

    public static PreparedEmote from(LoadedAnimation source) {
        return from(source, SKIN_BINDING_COMPILER.compile(source.animation()));
    }

    public static PreparedEmote from(LoadedAnimation source, List<SkinBinding> skinBindings) {
        Objects.requireNonNull(source, "source");
        skinBindings = List.copyOf(skinBindings);
        EmoteAnimation animation = source.animation();
        Objects.requireNonNull(animation, "animation");
        Map<String, PreparedTransform> defaultTransforms = new HashMap<>();

        animation.nodes().forEach((nodeId, node) -> defaultTransforms.put(
            nodeId,
            PreparedTransform.create(node.transform(), node instanceof EmoteAnimation.AnchorNode)
        ));
        Map<Integer, List<PreparedEvent>> eventsByTick = new HashMap<>();
        for (EmoteAnimation.TimelineEvent event : animation.timeline().events().timeline()) {
            eventsByTick.computeIfAbsent(event.tick(), ignored -> new ArrayList<>()).add(new PreparedEvent(
                event.event(), animation.id(), event.tick(), AnimationEventPhase.TIMELINE
            ));
        }

        return new PreparedEmote(
            source,
            skinBindings,
            animation,
            PreparedAnimationTimeline.compile(animation),
            copyListMap(eventsByTick),
            Map.copyOf(defaultTransforms),
            (int) animation.nodes().values().stream().filter(node -> !(node instanceof EmoteAnimation.AnchorNode)).count(),
            List.of(),
            Map.of(),
            animationTimeline(animation)
        );
    }

    static PreparedEmote sequence(
        PreparedEmote layout,
        List<PlaybackSegment> playbackSegments,
        Map<Integer, Set<String>> hiddenNodes,
        List<PlaybackTimeline.Segment> timelineSegments
    ) {
        Objects.requireNonNull(layout, "layout");
        Map<Integer, Set<String>> copiedHiddenNodes = new HashMap<>();
        hiddenNodes.forEach((tick, nodeIds) -> copiedHiddenNodes.put(tick, Set.copyOf(nodeIds)));
        return new PreparedEmote(
            layout.source,
            layout.skinBindings,
            layout.model,
            layout.preparedTimeline,
            Map.of(),
            compileSequenceDefaultTransforms(layout),
            layout.displayNodeCount,
            List.copyOf(playbackSegments),
            Map.copyOf(copiedHiddenNodes),
            new PlaybackTimeline(layout.model.id(), layout.durationTicks(), EmoteAnimation.LoopMode.ONCE,
                0, timelineSegments)
        );
    }

    private static PlaybackTimeline animationTimeline(EmoteAnimation animation) {
        int duration = animation.timeline().durationTicks();
        var playback = animation.settings().playback();
        List<PlaybackTimeline.Segment> segments = new ArrayList<>();
        segments.add(new PlaybackTimeline.Segment(0, null, null, PlaybackTimeline.Phase.ANIMATION,
            0, (long) duration, animation.id()));
        if ((playback.mode() == EmoteAnimation.LoopMode.LOOP || playback.mode() == EmoteAnimation.LoopMode.SERVER_SYNC)
            && playback.loopDelayTicks() > 0) {
            segments.add(new PlaybackTimeline.Segment(1, null, null, PlaybackTimeline.Phase.LOOP_DELAY,
                duration, (long) duration + playback.loopDelayTicks(), null));
        } else if (playback.mode() == EmoteAnimation.LoopMode.HOLD) {
            segments.add(new PlaybackTimeline.Segment(1, null, null, PlaybackTimeline.Phase.HOLD, duration, null, animation.id()));
        }
        return new PlaybackTimeline(animation.id(), duration, playback.mode(), playback.loopStartTicks(), segments);
    }

    private static Map<String, PreparedTransform> compileSequenceDefaultTransforms(PreparedEmote layout) {
        Map<String, Matrix4f> worldMatrices = new HashMap<>();
        Map<String, PreparedTransform> transforms = new HashMap<>();
        for (String nodeId : layout.preparedTimeline.nodeOrder()) {
            EmoteAnimation.Node node = layout.model.nodes().get(nodeId);
            Matrix4f world = new Matrix4f(layout.defaultTransforms.get(nodeId).localMatrix());
            if (node.parentId() != null) {
                world.set(worldMatrices.get(node.parentId())).mul(layout.defaultTransforms.get(nodeId).localMatrix());
            }
            worldMatrices.put(nodeId, world);
            transforms.put(nodeId, PreparedTransform.create(world, node instanceof EmoteAnimation.AnchorNode));
        }
        return Map.copyOf(transforms);
    }

    public LoadedAnimation source() {
        return this.source;
    }

    public EmoteAnimation model() {
        return this.model;
    }

    public PreparedAnimationTimeline preparedTimeline() {
        return this.preparedTimeline;
    }

    public String id() {
        return model().id().toString();
    }

    public EmoteMetadata metadata() {
        return model().metadata();
    }

    @Override
    public boolean standalone() {
        return model().settings().standalone();
    }

    public EmotePlayerBehavior playerBehavior() {
        return model().settings().player();
    }

    public Path sourcePath() {
        return this.source.sourcePath();
    }

    public int nodeCount() {
        return model().nodes().size();
    }

    @Override
    public int durationTicks() {
        return model().timeline().durationTicks();
    }

    @Override
    public int cooldownTicks() {
        return model().settings().cooldownTicks();
    }

    @Override
    public EmoteAnimation.LoopMode loopMode() {
        return model().settings().playback().mode();
    }

    public List<SkinBinding> skinBindings() {
        return this.skinBindings;
    }

    public List<PreparedEvent> timelineEvents(int tick) {
        return this.timelineEvents.getOrDefault(tick, List.of());
    }

    public int displayNodeCount() {
        return this.displayNodeCount;
    }

    public List<PlaybackSegment> playbackSegments() {
        return this.playbackSegments;
    }

    public PlaybackTimeline playbackTimeline() {
        return this.playbackTimeline;
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
        int transitionStartTick,
        int startTick,
        int endTick,
        PreparedEmote animation,
        int timelineSegmentIndex
    ) {
        public PlaybackSegment {
            if (transitionStartTick < 0 || startTick < transitionStartTick || endTick < startTick || timelineSegmentIndex < 0) {
                throw new IllegalArgumentException("invalid playback segment range");
            }
            Objects.requireNonNull(animation, "animation");
        }
    }

    public record PreparedEvent(
        EmoteAnimation.Event event,
        Identifier animationId,
        int animationTick,
        AnimationEventPhase phase
    ) {
        public PreparedEvent {
            Objects.requireNonNull(event, "event");
            Objects.requireNonNull(animationId, "animationId");
            Objects.requireNonNull(phase, "phase");
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
            Vec3 position = transform.position();
            Vec3 rotation = transform.rotation();
            Vec3 scale = transform.scale();
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
