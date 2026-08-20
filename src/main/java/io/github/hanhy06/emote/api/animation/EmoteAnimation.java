package io.github.hanhy06.emote.api.animation;

import com.google.gson.JsonElement;
import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.ParticipantRole;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record EmoteAnimation(
    Identifier id,
    EmoteMetadata metadata,
    Settings settings,
    MolangPrograms molang,
    Map<String, Node> nodes,
    Timeline timeline
) {
    public EmoteAnimation(Identifier id, EmoteMetadata metadata, Settings settings, Map<String, Node> nodes, Timeline timeline) {
        this(id, metadata, settings, MolangPrograms.empty(), nodes, timeline);
    }

    public EmoteAnimation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(molang, "molang");
        nodes = Map.copyOf(nodes);
        Objects.requireNonNull(timeline, "timeline");
    }

    public record MolangPrograms(String initialize, String tick) {
        public static MolangPrograms empty() {
            return new MolangPrograms(null, null);
        }
    }

    public record Settings(
        boolean standalone,
        int cooldownTicks,
        EmotePlayerBehavior player,
        PlaybackSettings playback
    ) {
        public Settings {
            if (cooldownTicks < 0) {
                throw new IllegalArgumentException("cooldown must not be negative");
            }
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(playback, "playback");
        }
    }

    public record PlaybackSettings(LoopMode mode, int loopDelayTicks) {
        public PlaybackSettings {
            Objects.requireNonNull(mode, "mode");
            if (loopDelayTicks < 0) {
                throw new IllegalArgumentException("loop delay must not be negative");
            }
            if ((mode == LoopMode.ONCE || mode == LoopMode.HOLD) && loopDelayTicks != 0) {
                throw new IllegalArgumentException("loop delay must be zero when playback mode is once or hold");
            }
        }
    }

    public record Matrix(List<Double> values) {
        public Matrix {
            values = List.copyOf(values);
            if (values.size() != 16) {
                throw new IllegalArgumentException("matrix must contain 16 values");
            }
        }

        public double value(int index) {
            return this.values.get(index);
        }
    }

    public sealed interface Node permits ItemNode, BlockNode, TextNode, AnchorNode {
        NodeSpace space();

        String parentId();

        LocalTransform transform();

        Matrix defaultMatrix();

        default boolean visible() {
            return true;
        }

        default CompoundTag entityNbt() {
            return new CompoundTag();
        }
    }

    public record ItemNode(
        boolean visible,
        NodeSpace space,
        String parentId,
        LocalTransform transform,
        Matrix defaultMatrix,
        CompoundTag entityNbt,
        CompoundTag itemStackNbt,
        String itemDisplay,
        Skin skin
    ) implements Node {
        public ItemNode(
            boolean visible,
            NodeSpace space,
            Matrix defaultMatrix,
            CompoundTag entityNbt,
            CompoundTag itemStackNbt,
            String itemDisplay,
            Skin skin
        ) {
            this(visible, space, null, LocalTransform.IDENTITY, defaultMatrix, entityNbt, itemStackNbt, itemDisplay, skin);
        }

        public ItemNode {
            Objects.requireNonNull(space, "space");
            Objects.requireNonNull(transform, "transform");
            Objects.requireNonNull(defaultMatrix, "defaultMatrix");
            entityNbt = copy(entityNbt);
            itemStackNbt = copy(itemStackNbt);
            Objects.requireNonNull(itemDisplay, "itemDisplay");
        }
    }

    public record BlockNode(
        boolean visible,
        NodeSpace space,
        String parentId,
        LocalTransform transform,
        Matrix defaultMatrix,
        CompoundTag entityNbt,
        CompoundTag blockStateNbt
    ) implements Node {
        public BlockNode(
            boolean visible,
            NodeSpace space,
            Matrix defaultMatrix,
            CompoundTag entityNbt,
            CompoundTag blockStateNbt
        ) {
            this(visible, space, null, LocalTransform.IDENTITY, defaultMatrix, entityNbt, blockStateNbt);
        }

        public BlockNode {
            Objects.requireNonNull(space, "space");
            Objects.requireNonNull(transform, "transform");
            Objects.requireNonNull(defaultMatrix, "defaultMatrix");
            entityNbt = copy(entityNbt);
            blockStateNbt = copy(blockStateNbt);
        }
    }

    public record TextNode(
        boolean visible,
        NodeSpace space,
        String parentId,
        LocalTransform transform,
        Matrix defaultMatrix,
        CompoundTag entityNbt,
        JsonElement text
    ) implements Node {
        public TextNode(
            boolean visible,
            NodeSpace space,
            Matrix defaultMatrix,
            CompoundTag entityNbt,
            JsonElement text
        ) {
            this(visible, space, null, LocalTransform.IDENTITY, defaultMatrix, entityNbt, text);
        }

        public TextNode {
            Objects.requireNonNull(space, "space");
            Objects.requireNonNull(transform, "transform");
            Objects.requireNonNull(defaultMatrix, "defaultMatrix");
            entityNbt = copy(entityNbt);
            text = Objects.requireNonNull(text, "text").deepCopy();
        }

        @Override
        public JsonElement text() {
            return this.text.deepCopy();
        }
    }

    public record AnchorNode(
        NodeSpace space,
        String parentId,
        LocalTransform transform,
        Matrix defaultMatrix
    ) implements Node {
        public AnchorNode(NodeSpace space, Matrix defaultMatrix) {
            this(space, null, LocalTransform.IDENTITY, defaultMatrix);
        }

        public AnchorNode {
            Objects.requireNonNull(space, "space");
            Objects.requireNonNull(transform, "transform");
            Objects.requireNonNull(defaultMatrix, "defaultMatrix");
        }
    }

    public record LocalTransform(Vec3 position, Vec3 rotation, Vec3 scale) {
        public static final LocalTransform IDENTITY = new LocalTransform(Vec3.ZERO, Vec3.ZERO, new Vec3(1.0D, 1.0D, 1.0D));

        public LocalTransform {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(rotation, "rotation");
            Objects.requireNonNull(scale, "scale");
        }

        public Matrix matrix() {
            double x = Math.toRadians(this.rotation.x());
            double y = Math.toRadians(this.rotation.y());
            double z = Math.toRadians(this.rotation.z());
            double sx = Math.sin(x);
            double cx = Math.cos(x);
            double sy = Math.sin(y);
            double cy = Math.cos(y);
            double sz = Math.sin(z);
            double cz = Math.cos(z);
            double scaleX = this.scale.x();
            double scaleY = this.scale.y();
            double scaleZ = this.scale.z();
            return new Matrix(List.of(
                cy * cz * scaleX,
                -cy * sz * scaleY,
                sy * scaleZ,
                this.position.x(),
                (sx * sy * cz + cx * sz) * scaleX,
                (-sx * sy * sz + cx * cz) * scaleY,
                -sx * cy * scaleZ,
                this.position.y(),
                (-cx * sy * cz + sx * sz) * scaleX,
                (cx * sy * sz + sx * cz) * scaleY,
                cx * cy * scaleZ,
                this.position.z(),
                0.0D,
                0.0D,
                0.0D,
                1.0D
            ));
        }
    }

    public enum NodeSpace {
        SCENE,
        INITIATOR,
        PARTNER;

        public static NodeSpace forParticipant(ParticipantRole participant) {
            return switch (participant) {
                case INITIATOR -> INITIATOR;
                case PARTNER -> PARTNER;
            };
        }
    }

    public record Skin(ParticipantRole participant, SkinPart part, int order) {
        public Skin {
            Objects.requireNonNull(participant, "participant");
            Objects.requireNonNull(part, "part");
            if (order < 0) {
                throw new IllegalArgumentException("skin order must not be negative");
            }
        }
    }

    public enum SkinPart {
        HEAD,
        BODY,
        LEFT_ARM,
        RIGHT_ARM,
        LEFT_LEG,
        RIGHT_LEG
    }

    public record Timeline(
        int durationTicks,
        Map<String, NodeTracks> tracks,
        List<Keyframe> keyframes,
        Events events
    ) {
        public Timeline(int durationTicks, List<Keyframe> keyframes, Events events) {
            this(durationTicks, Map.of(), keyframes, events);
        }

        public Timeline {
            tracks = Map.copyOf(tracks);
            keyframes = List.copyOf(keyframes);
            Objects.requireNonNull(events, "events");
        }
    }

    public record NodeTracks(
        List<VectorKeyframe> position,
        List<VectorKeyframe> rotation,
        List<VectorKeyframe> scale,
        List<VisibilityKeyframe> visible
    ) {
        public NodeTracks {
            position = List.copyOf(position);
            rotation = List.copyOf(rotation);
            scale = List.copyOf(scale);
            visible = List.copyOf(visible);
        }
    }

    public record VectorKeyframe(
        int tick,
        VectorValue pre,
        VectorValue post,
        Interpolation interpolation,
        Easing easing
    ) {
        public VectorKeyframe {
            Objects.requireNonNull(pre, "pre");
            Objects.requireNonNull(post, "post");
            Objects.requireNonNull(interpolation, "interpolation");
            Objects.requireNonNull(easing, "easing");
        }
    }

    public record VectorValue(ScalarValue x, ScalarValue y, ScalarValue z) {
        public VectorValue {
            Objects.requireNonNull(x, "x");
            Objects.requireNonNull(y, "y");
            Objects.requireNonNull(z, "z");
        }
    }

    public sealed interface ScalarValue permits ConstantValue, MolangValue {
    }

    public record ConstantValue(double value) implements ScalarValue {
        public ConstantValue {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("constant value must be finite");
            }
        }
    }

    public record MolangValue(String source, String path) implements ScalarValue {
        public MolangValue {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(path, "path");
        }
    }

    public record VisibilityKeyframe(int tick, VisibilityValue value) {
        public VisibilityKeyframe {
            Objects.requireNonNull(value, "value");
        }
    }

    public sealed interface VisibilityValue permits ConstantVisibility, MolangVisibility {
    }

    public record ConstantVisibility(boolean value) implements VisibilityValue {
    }

    public record MolangVisibility(String source, String path) implements VisibilityValue {
        public MolangVisibility {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(path, "path");
        }
    }

    public enum Interpolation {
        STEP,
        LINEAR
    }

    public enum Easing {
        LINEAR,
        EASE_IN_SINE,
        EASE_OUT_SINE,
        EASE_IN_OUT_SINE,
        EASE_IN_QUAD,
        EASE_OUT_QUAD,
        EASE_IN_OUT_QUAD,
        EASE_IN_CUBIC,
        EASE_OUT_CUBIC,
        EASE_IN_OUT_CUBIC,
        EASE_IN_QUART,
        EASE_OUT_QUART,
        EASE_IN_OUT_QUART,
        EASE_IN_QUINT,
        EASE_OUT_QUINT,
        EASE_IN_OUT_QUINT,
        EASE_IN_EXPO,
        EASE_OUT_EXPO,
        EASE_IN_OUT_EXPO,
        EASE_IN_CIRC,
        EASE_OUT_CIRC,
        EASE_IN_OUT_CIRC,
        EASE_IN_BACK,
        EASE_OUT_BACK,
        EASE_IN_OUT_BACK,
        EASE_IN_ELASTIC,
        EASE_OUT_ELASTIC,
        EASE_IN_OUT_ELASTIC,
        EASE_IN_BOUNCE,
        EASE_OUT_BOUNCE,
        EASE_IN_OUT_BOUNCE
    }

    public enum LoopMode {
        ONCE,
        HOLD,
        LOOP,
        SERVER_SYNC
    }

    public record Keyframe(
        int tick,
        Map<String, NodeTransform> nodeTransforms,
        Map<String, NodeState> nodeStates
    ) {
        public Keyframe {
            nodeTransforms = Map.copyOf(nodeTransforms);
            nodeStates = Map.copyOf(nodeStates);
        }
    }

    public record NodeTransform(Matrix matrix, int interpolationDurationTicks) {
        public NodeTransform {
            Objects.requireNonNull(matrix, "matrix");
        }
    }

    public record NodeState(boolean visible) {
    }

    public record Events(
        List<Event> start,
        List<TimelineEvent> timeline,
        List<Event> loop,
        List<Event> stop
    ) {
        public Events {
            start = List.copyOf(start);
            timeline = List.copyOf(timeline);
            loop = List.copyOf(loop);
            stop = List.copyOf(stop);
        }

        public static Events empty() {
            return new Events(List.of(), List.of(), List.of(), List.of());
        }
    }

    public record Event(CommandSource source, CommandOrigin origin, List<String> commands) {
        public Event {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(origin, "origin");
            commands = List.copyOf(commands);
        }
    }

    public record TimelineEvent(int tick, CommandSource source, CommandOrigin origin, List<String> commands) {
        public TimelineEvent {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(origin, "origin");
            commands = List.copyOf(commands);
        }

        public Event event() {
            return new Event(this.source, this.origin, this.commands);
        }
    }

    public record CommandSource(SourceType type, String node) {
        public CommandSource {
            Objects.requireNonNull(type, "type");
        }
    }

    public enum SourceType {
        PLAYER,
        SERVER,
        NODE
    }

    public record CommandOrigin(OriginType type, String node, Vec3 offset) {
        public CommandOrigin {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(offset, "offset");
        }
    }

    public enum OriginType {
        ROOT,
        NODE
    }

    public record Vec3(double x, double y, double z) {
        public static final Vec3 ZERO = new Vec3(0.0D, 0.0D, 0.0D);
    }

    private static CompoundTag copy(CompoundTag tag) {
        return Objects.requireNonNull(tag, "tag").copy();
    }
}
