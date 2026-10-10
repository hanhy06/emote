package io.github.hanhy06.emote.api.animation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.skin.model.PlayerSkinPart;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import io.github.hanhy06.emote.api.EmoteCallback;

public record EmoteAnimation(Identifier id, EmoteMetadata metadata, Settings settings, MolangPrograms molang,
                             Map<String, Node> nodes, Clip clip, List<EmoteCallback> callbacks,
                             String targetMinecraftVersion, JsonObject resources, JsonObject source) {
    public static final int SCHEMA_VERSION = 5;
    public static final Set<String> RUNTIME_NBT_FIELDS = Set.of("id", "UUID", "Pos", "Motion", "Rotation", "Passengers", "Tags",
        "transformation", "interpolation_duration", "start_interpolation", "teleport_duration");

    public EmoteAnimation(Identifier id, EmoteMetadata metadata, Settings settings, MolangPrograms molang,
                          Map<String, Node> nodes, Clip clip, List<EmoteCallback> callbacks) {
        this(id, metadata, settings, molang, nodes, clip, callbacks, null, new JsonObject(), new JsonObject());
    }

    public EmoteAnimation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(molang, "molang");
        nodes = Map.copyOf(nodes);
        Objects.requireNonNull(clip, "clip");
        callbacks = List.copyOf(callbacks);
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(source, "source");
    }

    public record MolangPrograms(String initialize, String update) {
        public static MolangPrograms empty() { return new MolangPrograms(null, null); }
    }
    public record Settings(boolean standalone, int cooldownTicks, float rotationDeadzone,
                           EmotePlayerBehavior playerBehavior, PlaybackSettings playback) {
        public Settings {
            if (cooldownTicks < 0) throw new IllegalArgumentException("Invalid cooldown");
            if (!Float.isFinite(rotationDeadzone) || rotationDeadzone < 0 || rotationDeadzone > 180) throw new IllegalArgumentException("Invalid rotation deadzone");
            Objects.requireNonNull(playerBehavior); Objects.requireNonNull(playback);
        }
    }
    public record PlaybackSettings(PlaybackMode mode, int loopStartTick, ScalarValue startDelay, ScalarValue loopDelay) {
        public PlaybackSettings(PlaybackMode mode, int loopStartTick, int loopDelayTicks) {
            this(mode, loopStartTick, new ConstantValue(0), new ConstantValue(loopDelayTicks));
        }
        public PlaybackSettings {
            Objects.requireNonNull(mode); Objects.requireNonNull(startDelay); Objects.requireNonNull(loopDelay);
            if (loopStartTick < 0 || mode != PlaybackMode.LOOP && loopStartTick != 0) throw new IllegalArgumentException("Invalid loop start");
            for (ScalarValue delay : List.of(startDelay, loopDelay)) {
                if (delay instanceof ConstantValue c && (c.value() < 0 || c.value() > Integer.MAX_VALUE || c.value() != Math.rint(c.value()))) throw new IllegalArgumentException("Delay must be a non-negative integer tick count");
            }
        }
    }
    public record Node(String name, String parentId, Inheritance inherit, boolean visible,
                       List<Operation> transform, Map<String, Attachment> attachments, JsonObject source) {
        public Node(String parentId, List<Operation> transform, Map<String, Attachment> attachments) {
            this(null, parentId, Inheritance.DEFAULT, true, transform, attachments, new JsonObject());
        }
        public Node {
            Objects.requireNonNull(inherit); transform = List.copyOf(transform); attachments = Map.copyOf(attachments);
            Objects.requireNonNull(source);
        }
    }
    public record Inheritance(RotationInheritance rotation, boolean scale, boolean visibility) {
        public static final Inheritance DEFAULT = new Inheritance(RotationInheritance.PARENT, true, true);
    }
    public enum RotationInheritance { PARENT, ENTITY }
    public enum OperationType { TRANSLATE, ROTATE_EULER, ROTATE_QUATERNION, SCALE, MATRIX }
    public enum RotationOrder { XYZ, XZY, YXZ, YZX, ZXY, ZYX }
    public record Operation(String id, OperationType op, RotationOrder order, List<Double> value) {
        public Operation { Objects.requireNonNull(id); Objects.requireNonNull(op); value = List.copyOf(value); }
    }
    public sealed interface Attachment permits ItemAttachment, BlockAttachment, TextAttachment, SkinAttachment, ExternalAttachment {
        boolean visible();
        default CompoundTag entityNbt() { return new CompoundTag(); }
    }
    public record ItemAttachment(boolean visible, CompoundTag entityNbt, CompoundTag itemStackNbt, String itemDisplay) implements Attachment {}
    public record BlockAttachment(boolean visible, CompoundTag entityNbt, CompoundTag blockStateNbt) implements Attachment {}
    public record TextAttachment(boolean visible, CompoundTag entityNbt, JsonElement text) implements Attachment {}
    public record SkinAttachment(boolean visible, PlayerSkinPart part, double from, double to) implements Attachment {}
    public record ExternalAttachment(boolean visible, Identifier key, JsonElement data) implements Attachment {}
    public record Clip(int durationTicks, Map<String, Track> tracks, Events events, String clock) {
        public Clip(int durationTicks, Map<String, Track> tracks, Events events) { this(durationTicks, tracks, events, null); }
        public Clip {
            if (durationTicks <= 0) throw new IllegalArgumentException("Invalid duration");
            tracks = Map.copyOf(tracks); Objects.requireNonNull(events);
        }
    }
    public record Target(String node, String operation, String attachment) {}
    public enum Channel { VALUE, VISIBLE, NBT }
    public record Track(Target target, Channel channel, Driver driver) {}
    public enum DriverType { EXPRESSION, CURVE, STATE }
    public record Driver(DriverType type, Value value, List<Keyframe> keys, List<Segment> segments, boolean firstPre) {
        public Driver { keys = List.copyOf(keys); segments = List.copyOf(segments); }
    }
    public record Keyframe(int tick, Value pre, Value post) {}
    public enum Interpolation { STEP, LINEAR, SLERP, CATMULL_ROM, HERMITE, BEZIER }
    public record Segment(Interpolation interpolation, Easing easing, double tension, VectorValue previous,
                          VectorValue following, VectorValue outTangent, VectorValue inTangent, List<BezierHandle> handles) {
        public Segment { handles = List.copyOf(handles); }
    }
    public record BezierHandle(double outTime, ScalarValue outValue, double inTime, ScalarValue inValue) {}
    public record Easing(String kernel, String direction, double parameter) {}
    public sealed interface Value permits VectorValue, VisibilityValue, NbtValue {}
    public record VectorValue(List<ScalarValue> components) implements Value {
        public VectorValue { components = List.copyOf(components); }
    }
    public sealed interface ScalarValue permits ConstantValue, MolangValue {}
    public record ConstantValue(double value) implements ScalarValue {
        public ConstantValue { if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite value"); }
    }
    public record MolangValue(String source, String path) implements ScalarValue {}
    public sealed interface VisibilityValue extends Value permits ConstantVisibility, MolangVisibility {}
    public record ConstantVisibility(boolean value) implements VisibilityValue {}
    public record MolangVisibility(String source, String path) implements VisibilityValue {}
    public sealed interface NbtValue extends Value permits ConstantNbtValue, MolangNbtValue {}
    public record ConstantNbtValue(CompoundTag value, List<String> remove) implements NbtValue {
        public ConstantNbtValue { remove = List.copyOf(remove); }
    }
    public record MolangNbtValue(String source, String path, List<String> remove) implements NbtValue {
        public MolangNbtValue { remove = List.copyOf(remove); }
    }
    public enum PlaybackMode { ONCE, HOLD, LOOP, SERVER_SYNC }
    public record Events(List<Event> start, List<TimelineEvent> timeline, List<Event> loop, List<Event> stop) {
        public Events { start = List.copyOf(start); timeline = List.copyOf(timeline); loop = List.copyOf(loop); stop = List.copyOf(stop); }
        public static Events empty() { return new Events(List.of(), List.of(), List.of(), List.of()); }
    }
    public record Event(CommandSource source, CommandOrigin origin, List<String> commands, Identifier externalKey, JsonElement data) {
        public Event(CommandSource source, CommandOrigin origin, List<String> commands) { this(source, origin, commands, null, null); }
        public Event { commands = List.copyOf(commands); }
    }
    public record TimelineEvent(int tick, Direction direction, Event event) {}
    public enum Direction { FORWARD, BACKWARD, BOTH }
    public record CommandSource(SourceType type, String node, String attachment) {
        public CommandSource(SourceType type, String node) { this(type, node, null); }
    }
    public enum SourceType { PLAYER, SERVER, NODE }
    public record CommandOrigin(OriginType type, String node, Vec3 offset) {}
    public enum OriginType { ROOT, NODE }

}
