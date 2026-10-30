package io.github.hanhy06.emote.content.loader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.hanhy06.emote.api.EmoteLoadException;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.content.LoadedAnimation;
import io.github.hanhy06.emote.skin.model.PlayerSkinPart;
import io.github.hanhy06.emote.skin.model.PlayerSkinSource;
import io.github.hanhy06.emote.skin.model.SkinOverride;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.util.*;

import static io.github.hanhy06.emote.api.animation.EmoteAnimation.*;

public final class AnimationJsonParser {
    private static final Set<String> CONTENT_FIELDS = Set.of("item", "item_display", "block_state", "text");

    public LoadedAnimation parse(Path path) throws EmoteLoadException { return parse(EmoteJsonDocument.read(path)); }
    public LoadedAnimation parse(Path path, byte[] bytes) throws EmoteLoadException { return parse(EmoteJsonDocument.parse(path, bytes)); }

    LoadedAnimation parse(EmoteJsonDocument document) throws EmoteLoadException {
        JsonObject root = document.root();
        if (!document.type().equals("animation")) throw document.error("$.type", "must equal animation");
        document.requireExactInt(root, "schema_version", "$", SCHEMA_VERSION);
        Identifier id = document.requireIdentifier(document.requireString(root, "id", "$"), "$.id");
        Map<String, Node> nodes = nodes(document.requireObject(root, "nodes", "$"), document);
        JsonObject clip = document.requireObject(root, "animation", "$");
        int durationTicks = document.requireTicks(clip, "duration", "$.animation", 1);
        if (durationTicks > 12000) throw document.error("$.animation.duration", "must not exceed 12000 ticks");
        JsonObject playback = object(clip, "playback", "$.animation", document);
        PlaybackMode mode = enumeration(PlaybackMode.class, text(playback, "mode", "once", "$.animation.playback", document), "$.animation.playback.mode", document);
        int loopStartTick = playback.has("loop_start") ? document.requireTicks(playback, "loop_start", "$.animation.playback", 0) : 0;
        if (loopStartTick >= durationTicks || mode != PlaybackMode.LOOP && loopStartTick != 0) throw document.error("$.animation.playback.loop_start", "invalid loop start");
        ScalarValue startDelay = delay(playback, "start_delay", document), loopDelay = delay(playback, "loop_delay", document);
        JsonObject settings = object(root, "settings", "$", document);
        int cooldownTicks = settings.has("cooldown") ? document.requireTicks(settings, "cooldown", "$.settings", 0) : 0;
        double deadzone = number(settings, "rotation_deadzone", 50, "$.settings", document);
        if (deadzone < 0 || deadzone > 180) throw document.error("$.settings.rotation_deadzone", "must be between 0 and 180");
        EmotePlayerBehavior playerBehavior = parsePlayerBehavior(object(settings, "player", "$.settings", document), document);
        JsonObject molangObject = object(clip, "molang", "$.animation", document);
        Molang molang = new Molang(molang(molangObject, "initialize", "$.animation.molang", document), molang(molangObject, "tick", "$.animation.molang", document));
        JsonObject clock = object(clip, "clock", "$.animation", document);
        String clockType = text(clock, "type", "elapsed", "$.animation.clock", document);
        if (!Set.of("elapsed", "molang").contains(clockType)) throw document.error("$.animation.clock.type", "unknown clock");
        String expression = clockType.equals("molang") ? molang(clock, "expression", "$.animation.clock", document) : null;
        if (clockType.equals("molang") && expression == null) throw document.error("$.animation.clock.expression", "is required");
        List<Track> tracks = tracks(document.requireArray(clip, "tracks", "$.animation"), nodes, durationTicks, document);
        Events events = events(object(clip, "events", "$.animation", document), nodes, durationTicks, document);
        return new LoadedAnimation(document.sourcePath(), new EmoteAnimation(id,
            document.metadata(),
            new Settings(bool(settings, "standalone", true, "$.settings", document), cooldownTicks, (float) deadzone, playerBehavior,
                new PlaybackSettings(mode, loopStartTick, startDelay, loopDelay)),
            molang, nodes, new Clip(durationTicks, tracks, events, expression, sharedValues(object(clip, "values", "$.animation", document), durationTicks, document)), document.callbacks(),
            root.has("target_minecraft_version") ? document.requireString(root, "target_minecraft_version", "$") : null,
            object(root, "resources", "$", document), object(root, "source", "$", document), skin(root, document)));
    }

    private Skin skin(JsonObject root, EmoteJsonDocument document) throws EmoteLoadException {
        JsonObject skin = object(root, "skin", "$", document);
        PlayerSkinSource defaultSource = null;
        if (skin.has("default")) {
            try {
                byte[] png = Base64.getDecoder().decode(document.requireString(skin, "default", "$.skin"));
                PlayerSkinSource.readPng(png);
                defaultSource = PlayerSkinSource.fromPng(png);
            } catch (IllegalArgumentException | java.io.IOException exception) {
                throw document.error("$.skin.default", exception.getMessage());
            }
        }
        SkinOverride override = null;
        if (skin.has("override")) {
            JsonObject object = document.requireObject(skin, "override", "$.skin");
            String texture = document.requireString(object, "texture", "$.skin.override");
            String mask = document.requireString(object, "mask", "$.skin.override");
            try {
                override = new SkinOverride(Base64.getDecoder().decode(texture), Base64.getDecoder().decode(mask));
            } catch (IllegalArgumentException | java.io.IOException exception) {
                throw document.error("$.skin.override", exception.getMessage());
            }
        }
        return new Skin(defaultSource, override);
    }

    private EmotePlayerBehavior parsePlayerBehavior(JsonObject player, EmoteJsonDocument document) throws EmoteLoadException {
        JsonObject stop = object(player, "stop_conditions", "$.settings.player", document);
        double distance = number(stop, "movement_distance", 0.3, "$.settings.player.stop_conditions", document);
        if (distance < 0) throw document.error("$.settings.player.stop_conditions.movement_distance", "must not be negative");
        String p = "$.settings.player.stop_conditions";
        return new EmotePlayerBehavior(bool(player, "hidden", true, "$.settings.player", document),
            new EmotePlayerBehavior.StopConditions(distance, bool(stop, "jump", true, p, document), bool(stop, "submerge", true, p, document),
                bool(stop, "ride", true, p, document), bool(stop, "damage", true, p, document), bool(stop, "attack", true, p, document), bool(stop, "game_mode_change", true, p, document)));
    }

    private Map<String, Node> nodes(JsonObject input, EmoteJsonDocument document) throws EmoteLoadException {
        Map<String, Node> nodes = new LinkedHashMap<>();
        for (var entry : input.entrySet()) {
            String path = "$.nodes." + entry.getKey();
            if (entry.getKey().isBlank()) throw document.error("$.nodes", "node id must not be blank");
            JsonObject n = document.requireObject(entry.getValue(), path);
            List<Operation> operations = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            JsonArray transforms = document.optionalArray(n, "transform", path);
            if (transforms != null) for (int i = 0; i < transforms.size(); i++) {
                String p = path + ".transform[" + i + "]";
                JsonObject o = document.requireObject(transforms.get(i), p);
                String id = nonempty(o, "id", p, document);
                if (!ids.add(id)) throw document.error(p + ".id", "duplicate operation");
                OperationType op = enumeration(OperationType.class, nonempty(o, "op", p, document), p + ".op", document);
                RotationOrder order = op == OperationType.ROTATE_EULER ? enumeration(RotationOrder.class, nonempty(o, "order", p, document), p + ".order", document) : null;
                int componentCount = op == OperationType.MATRIX ? 16 : op == OperationType.ROTATE_QUATERNION ? 4 : 3;
                List<Double> value = new ArrayList<>();
                VectorValue vector = vector(document.requireElement(o, "value", p), componentCount, true, p + ".value", document);
                for (ScalarValue v : vector.components()) value.add(((ConstantValue) v).value());
                if (componentCount == 4) {
                    double length = Math.sqrt(value.stream().mapToDouble(v -> v * v).sum());
                    if (length == 0 || !Double.isFinite(length)) throw document.error(p + ".value", "invalid quaternion");
                    value.replaceAll(v -> v / length);
                }
                operations.add(new Operation(id, op, order, value));
            }
            JsonObject inherit = object(n, "inherit", path, document);
            Inheritance inheritance = new Inheritance(enumeration(RotationInheritance.class, text(inherit, "rotation", "parent", path + ".inherit", document), path + ".inherit.rotation", document),
                bool(inherit, "scale", true, path + ".inherit", document), bool(inherit, "visibility", true, path + ".inherit", document));
            Map<String, Attachment> attachments = new TreeMap<>();
            for (var a : object(n, "attachments", path, document).entrySet()) {
                if (a.getKey().isBlank()) throw document.error(path + ".attachments", "attachment id must not be blank");
                String p = path + ".attachments." + a.getKey();
                JsonObject definition = document.requireObject(a.getValue(), p);
                String type = nonempty(definition, "type", p, document);
                boolean visible = bool(definition, "visible", true, p, document);
                CompoundTag nbt = definition.has("entity_nbt") ? compound(document.requireString(definition, "entity_nbt", p), p + ".entity_nbt", true, document) : new CompoundTag();
                Attachment attachment = switch (type) {
                    case "item_display" -> new ItemAttachment(visible, nbt, compound(document.requireString(definition, "item_stack_snbt", p), p + ".item_stack_snbt", false, document), nonempty(definition, "item_display", p, document));
                    case "block_display" -> new BlockAttachment(visible, nbt, compound(document.requireString(definition, "block_state_snbt", p), p + ".block_state_snbt", false, document));
                    case "text_display" -> new TextAttachment(visible, nbt, document.requireElement(definition, "text", p));
                    case "player_skin" -> {
                        PlayerSkinPart part = enumeration(PlayerSkinPart.class, nonempty(definition, "part", p, document), p + ".part", document);
                        JsonObject region = document.requireObject(definition, "region", p);
                        double from = number(region, "from", p + ".region", document), to = number(region, "to", p + ".region", document);
                        if (!(from >= 0 && from < to && to <= 1)) throw document.error(p + ".region", "must satisfy 0 <= from < to <= 1");
                        yield new SkinAttachment(visible, part, from, to);
                    }
                    case "external" -> new ExternalAttachment(visible, document.requireIdentifier(nonempty(definition, "key", p, document), p + ".key"), document.requireElement(definition, "data", p));
                    default -> throw document.error(p + ".type", "unknown attachment");
                };
                if (!(attachment instanceof ItemAttachment || attachment instanceof BlockAttachment || attachment instanceof TextAttachment) && definition.has("entity_nbt")) throw document.error(p + ".entity_nbt", "requires a display attachment");
                attachments.put(a.getKey(), attachment);
            }
            nodes.put(entry.getKey(), new Node(n.has("name") ? document.requireString(n, "name", path) : null,
                n.has("parent") ? nonempty(n, "parent", path, document) : null, inheritance, bool(n, "visible", true, path, document),
                operations, attachments, object(n, "source", path, document)));
        }
        return nodes;
    }

    private List<Track> tracks(JsonArray input, Map<String, Node> nodes, int durationTicks, EmoteJsonDocument document) throws EmoteLoadException {
        List<Track> result = new ArrayList<>();
        Set<List<String>> signatures = new HashSet<>();
        for (int i = 0; i < input.size(); i++) {
            String path = "$.animation.tracks[" + i + "]";
            JsonObject raw = document.requireObject(input.get(i), path), target = document.requireObject(raw, "target", path);
            String nodeId = nonempty(target, "node", path + ".target", document);
            Node node = document.requireNode(nodes, nodeId, path + ".target.node");
            String operationId = target.has("operation") ? nonempty(target, "operation", path + ".target", document) : null;
            String attachmentId = target.has("attachment") ? nonempty(target, "attachment", path + ".target", document) : null;
            if (operationId != null && attachmentId != null) throw document.error(path + ".target", "cannot target operation and attachment together");
            Operation op = null;
            if (operationId != null) {
                for (Operation candidate : node.transform()) if (candidate.id().equals(operationId)) op = candidate;
                if (op == null) throw document.error(path + ".target.operation", "unknown operation");
            }
            if (attachmentId != null && !node.attachments().containsKey(attachmentId)) throw document.error(path + ".target.attachment", "unknown attachment");
            Channel channel = enumeration(Channel.class, nonempty(raw, "channel", path, document), path + ".channel", document);
            if (channel == Channel.VALUE && op == null || channel != Channel.VALUE && op != null) throw document.error(path + ".target", "channel does not match target");
            if (channel == Channel.NBT && (attachmentId == null || !(node.attachments().get(attachmentId) instanceof ItemAttachment || node.attachments().get(attachmentId) instanceof BlockAttachment || node.attachments().get(attachmentId) instanceof TextAttachment))) throw document.error(path + ".target", "NBT requires a display attachment");
            Target destination = new Target(nodeId, operationId, attachmentId);
            List<String> key = List.of(nodeId, Objects.toString(operationId, ""), Objects.toString(attachmentId, ""), channel.name());
            if (!signatures.add(key)) throw document.error(path + ".target", "duplicate target/channel");
            int componentCount = op == null ? 0 : op.op() == OperationType.MATRIX ? 16 : op.op() == OperationType.ROTATE_QUATERNION ? 4 : 3;
            Blend blend = null;
            if (raw.has("blend")) {
                if (channel != Channel.VALUE || componentCount != 3) throw document.error(path + ".blend", "requires a three-component value track");
                JsonObject b = document.requireObject(raw, "blend", path);
                blend = new Blend(vector(document.requireElement(b, "base", path + ".blend"), componentCount, true, path + ".blend.base", document).components().stream()
                    .map(v -> ((ConstantValue) v).value()).toList(), scalar(document.requireElement(b, "weight", path + ".blend"), path + ".blend.weight", document));
            }
            result.add(new Track(destination, channel, driver(document.requireObject(raw, "driver", path), channel, componentCount, durationTicks, path + ".driver", document), blend));
        }
        return result;
    }

    private Map<String, SharedValue> sharedValues(JsonObject input, int durationTicks, EmoteJsonDocument document) throws EmoteLoadException {
        Map<String, SharedValue> result = new LinkedHashMap<>();
        for (var entry : input.entrySet()) {
            String path = "$.animation.values." + entry.getKey();
            if (entry.getKey().isBlank()) throw document.error(path, "value id must not be blank");
            JsonObject raw = document.requireObject(entry.getValue(), path);
            JsonArray base = document.requireArray(raw, "base", path);
            if (base.size() != 1 && base.size() != 3) throw document.error(path + ".base", "must contain one or three components");
            List<Double> components = vector(base, base.size(), true, path + ".base", document).components().stream().map(v -> ((ConstantValue) v).value()).toList();
            int timeOffsetTicks = raw.has("time_offset") ? document.requireTicks(raw, "time_offset", path, 0) : 0;
            result.put(entry.getKey(), new SharedValue(components, driver(raw, Channel.VALUE, base.size(), durationTicks, path, document), timeOffsetTicks));
        }
        return result;
    }

    private Driver driver(JsonObject driver, Channel channel, int componentCount, int durationTicks, String p, EmoteJsonDocument document) throws EmoteLoadException {
        DriverType driverType = enumeration(DriverType.class, nonempty(driver, "type", p, document), p + ".type", document);
        String before = text(driver, "before", "base", p, document);
        String keyPolicy = text(driver, "at_key", "post", p, document);
        if (!Set.of("pre", "post", "segment").contains(keyPolicy)) throw document.error(p + ".at_key", "invalid key policy");
        if (keyPolicy.equals("segment") && (driverType != DriverType.CURVE || componentCount != 1 && componentCount != 3)) throw document.error(p + ".at_key", "segment sampling requires a scalar or three-component curve");
        String evaluationOrder = text(driver, "evaluation", "vector", p, document);
        if (!Set.of("vector", "component").contains(evaluationOrder)) throw document.error(p + ".evaluation", "invalid evaluation order");
        if (evaluationOrder.equals("component") && (driverType != DriverType.CURVE || componentCount != 1 && componentCount != 3)) throw document.error(p + ".evaluation", "component evaluation requires a scalar or three-component curve");
        if (!Set.of("base", "first_pre").contains(before)) throw document.error(p + ".before", "invalid before policy");
        Value expression = null;
        List<Keyframe> keys = new ArrayList<>();
        List<Segment> segments = new ArrayList<>();
        if (driverType == DriverType.EXPRESSION) {
            if (channel == Channel.NBT) throw document.error(p, "requires a state curve");
            expression = value(document.requireElement(driver, "value", p), channel, componentCount, p + ".value", document);
        } else {
            if (driverType == DriverType.CURVE && channel != Channel.VALUE || driverType == DriverType.STATE && channel == Channel.VALUE) throw document.error(p + ".type", "driver does not match channel");
            JsonArray array = document.requireArray(driver, "keys", p);
            if (array.isEmpty()) throw document.error(p + ".keys", "must not be empty");
            int previousTick = -1;
            for (int k = 0; k < array.size(); k++) {
                String kp = p + ".keys[" + k + "]";
                JsonObject keyframe = document.requireObject(array.get(k), kp);
                int tick = document.requireTicks(keyframe, "time", kp, 0);
                if (driverType == DriverType.STATE && tick > durationTicks || tick < previousTick || tick == previousTick && channel != Channel.NBT) {
                    throw document.error(kp + ".time", driverType == DriverType.CURVE ? "must increase strictly and be non-negative" : channel == Channel.NBT ? "must not decrease and must be within duration" : "must increase strictly within duration");
                }
                previousTick = tick;
                Value pre, post;
                if (keyframe.has("value")) {
                    if (keyframe.has("pre") || keyframe.has("post")) throw document.error(kp, "value and pre/post are exclusive");
                    pre = post = value(document.requireElement(keyframe, "value", kp), channel, componentCount, kp + ".value", document);
                } else {
                    if (driverType == DriverType.STATE) throw document.error(kp + ".value", "is required");
                    pre = value(document.requireElement(keyframe, "pre", kp), channel, componentCount, kp + ".pre", document);
                    post = value(document.requireElement(keyframe, "post", kp), channel, componentCount, kp + ".post", document);
                }
                keys.add(new Keyframe(tick, pre, post));
            }
            if (driverType == DriverType.CURVE) {
                JsonArray arraySegments = document.requireArray(driver, "segments", p);
                if (arraySegments.size() != keys.size() - 1) throw document.error(p + ".segments", "must contain keys.length - 1 segments");
                for (int s = 0; s < arraySegments.size(); s++) segments.add(segment(document.requireObject(arraySegments.get(s), p + ".segments[" + s + "]"), componentCount, p + ".segments[" + s + "]", document));
            }
        }
        return new Driver(driverType, expression, keys, segments, before.equals("first_pre"), enumeration(KeyPolicy.class, keyPolicy, p + ".at_key", document), evaluationOrder.equals("component"));
    }

    private Segment segment(JsonObject raw, int componentCount, String path, EmoteJsonDocument document) throws EmoteLoadException {
        Interpolation interpolation = enumeration(Interpolation.class, nonempty(raw, "interpolation", path, document), path + ".interpolation", document);
        if (componentCount == 16 && interpolation != Interpolation.STEP || componentCount == 4 && interpolation != Interpolation.STEP && interpolation != Interpolation.SLERP || componentCount != 4 && interpolation == Interpolation.SLERP) throw document.error(path + ".interpolation", "does not match value dimension");
        Easing easing = null;
        if (raw.has("easing")) {
            if (interpolation == Interpolation.STEP) throw document.error(path + ".easing", "not allowed for step");
            JsonObject e = document.requireObject(raw, "easing", path);
            String ep = path + ".easing", kernel = nonempty(e, "kernel", ep, document);
            if (!Set.of("linear", "power", "sine", "expo", "circ", "back", "blockbench_elastic", "blockbench_bounce", "blockbench_expo", "blockbench_steps", "steps").contains(kernel)) throw document.error(ep + ".kernel", "unknown easing");
            String direction = kernel.equals("linear") ? "in" : nonempty(e, "direction", ep, document);
            if (!Set.of("in", "out", "in_out").contains(direction)) throw document.error(ep + ".direction", "invalid easing direction");
            String field = switch (kernel) {
                case "power" -> "exponent";
                case "back" -> "overshoot";
                case "blockbench_elastic" -> "frequency";
                case "blockbench_bounce" -> "bounciness";
                case "steps", "blockbench_steps" -> "count";
                default -> null;
            };
            ScalarValue parameter = field == null ? new ConstantValue(0) : e.has(field) ? scalar(e.get(field), ep + "." + field, document)
                : switch (kernel) {
                    case "back" -> new ConstantValue(1.70158);
                    case "blockbench_elastic" -> new ConstantValue(1);
                    case "blockbench_bounce" -> new ConstantValue(0.5);
                    default -> throw document.error(ep + "." + field, "is required");
                };
            if (parameter instanceof ConstantValue c && (kernel.equals("power") && c.value() <= 0
                || Set.of("steps", "blockbench_steps").contains(kernel) && (c.value() < 2 || c.value() != Math.floor(c.value())))) throw document.error(ep, "invalid easing parameter");
            if (interpolation == Interpolation.BEZIER && Set.of("back", "blockbench_elastic").contains(kernel)) throw document.error(ep, "overshoot easing is not supported for Bezier");
            easing = new Easing(kernel, direction, parameter);
        }
        List<BezierHandle> handles = new ArrayList<>();
        String controlOrder = text(raw, "control_order", "time_value", path, document);
        if (!Set.of("time_value", "in_out").contains(controlOrder)) throw document.error(path + ".control_order", "invalid control order");
        if (interpolation == Interpolation.BEZIER) {
            JsonArray h = document.requireArray(raw, "handles", path);
            if (h.size() != componentCount) throw document.error(path + ".handles", "must match value dimension");
            for (int i = 0; i < h.size(); i++) {
                String hp = path + ".handles[" + i + "]";
                JsonObject handle = document.requireObject(h.get(i), hp), out = document.requireObject(handle, "out", hp), in = document.requireObject(handle, "in", hp);
                ScalarValue outTime = scalar(document.requireElement(out, "time", hp + ".out"), hp + ".out.time", document), inTime = scalar(document.requireElement(in, "time", hp + ".in"), hp + ".in.time", document);
                String outClamp = text(out, "clamp", "none", hp + ".out", document), inClamp = text(in, "clamp", "none", hp + ".in", document);
                if (!Set.of("none", "rescale_value").contains(outClamp) || !Set.of("none", "rescale_value").contains(inClamp)) throw document.error(hp, "invalid time clamp policy");
                if (outClamp.equals("none") && outTime instanceof ConstantValue o && (o.value() < 0 || o.value() > 1)
                    || inClamp.equals("none") && inTime instanceof ConstantValue n && (n.value() < 0 || n.value() > 1)) throw document.error(hp, "time handles must be within [0, 1]");
                handles.add(new BezierHandle(outTime, scalar(document.requireElement(out, "value", hp + ".out"), hp + ".out.value", document), bool(out, "relative", false, hp + ".out", document), outClamp.equals("rescale_value"),
                    inTime, scalar(document.requireElement(in, "value", hp + ".in"), hp + ".in.value", document), bool(in, "relative", false, hp + ".in", document), inClamp.equals("rescale_value")));
            }
        }
        VectorValue out = optionalVector(raw, "out_tangent", componentCount, path, document), in = optionalVector(raw, "in_tangent", componentCount, path, document);
        if (interpolation == Interpolation.HERMITE && (out == null || in == null)) throw document.error(path, "Hermite tangents are required");
        return new Segment(interpolation, easing, number(raw, "tension", 0.5, path, document), optionalVector(raw, "previous", componentCount, path, document),
            optionalVector(raw, "following", componentCount, path, document), out, in, handles, controlOrder.equals("in_out"));
    }

    private Value value(JsonElement raw, Channel channel, int componentCount, String path, EmoteJsonDocument document) throws EmoteLoadException {
        if (channel == Channel.VALUE) return vector(raw, componentCount, false, path, document);
        if (channel == Channel.VISIBLE) {
            if (raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isBoolean()) return new ConstantVisibility(raw.getAsBoolean());
            ScalarValue expression = scalar(raw, path, document);
            if (!(expression instanceof MolangValue m)) throw document.error(path, "visibility requires boolean or Molang");
            return new MolangVisibility(m.source(), m.path());
        }
        JsonObject patch = document.requireObject(raw, path);
        List<String> remove = strings(document.optionalArray(patch, "remove", path), path + ".remove", document);
        for (String field : remove) if (RUNTIME_NBT_FIELDS.contains(field)) throw document.error(path + ".remove", "cannot remove runtime-owned field " + field);
        JsonElement merge = document.requireElement(patch, "merge", path);
        if (merge.isJsonPrimitive() && merge.getAsJsonPrimitive().isString()) return new ConstantNbtValue(compound(merge.getAsString(), path + ".merge", false, document, true), remove);
        ScalarValue expression = scalar(merge, path + ".merge", document);
        if (!(expression instanceof MolangValue m)) throw document.error(path + ".merge", "requires SNBT or Molang");
        return new MolangNbtValue(m.source(), m.path(), remove);
    }

    private VectorValue vector(JsonElement raw, int componentCount, boolean literal, String path, EmoteJsonDocument document) throws EmoteLoadException {
        if (!raw.isJsonArray() || raw.getAsJsonArray().size() != componentCount) throw document.error(path, "must contain " + componentCount + " values");
        List<ScalarValue> values = new ArrayList<>();
        for (int i = 0; i < componentCount; i++) values.add(literal ? new ConstantValue(document.requireFiniteDouble(raw.getAsJsonArray().get(i), path + "[" + i + "]")) : scalar(raw.getAsJsonArray().get(i), path + "[" + i + "]", document));
        return new VectorValue(values);
    }

    private Events events(JsonObject input, Map<String, Node> nodes, int durationTicks, EmoteJsonDocument document) throws EmoteLoadException {
        Map<String, List<Event>> phases = new HashMap<>();
        List<TimelineEvent> timeline = new ArrayList<>();
        for (var entry : input.entrySet()) {
            String phase = entry.getKey(), path = "$.animation.events." + phase;
            if (!Set.of("start", "timeline", "loop", "stop").contains(phase)) throw document.error(path, "unknown event phase");
            if (!entry.getValue().isJsonArray()) throw document.error(path, "must be an array");
            List<Event> events = new ArrayList<>();
            JsonArray array = entry.getValue().getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                String p = path + "[" + i + "]";
                JsonObject event = document.requireObject(array.get(i), p), source = document.requireObject(event, "source", p), origin = document.requireObject(event, "origin", p), action = document.requireObject(event, "action", p);
                SourceType sourceType = enumeration(SourceType.class, nonempty(source, "type", p + ".source", document), p + ".source.type", document);
                String sourceNode = sourceType == SourceType.NODE ? nonempty(source, "node", p + ".source", document) : null;
                String attachment = sourceType == SourceType.NODE ? nonempty(source, "attachment", p + ".source", document) : null;
                if (sourceNode != null) {
                    Attachment a = document.requireNode(nodes, sourceNode, p + ".source.node").attachments().get(attachment);
                    if (!(a instanceof ItemAttachment || a instanceof BlockAttachment || a instanceof TextAttachment || a instanceof SkinAttachment)) throw document.error(p + ".source.attachment", "requires an entity attachment");
                }
                OriginType originType = enumeration(OriginType.class, nonempty(origin, "type", p + ".origin", document), p + ".origin.type", document);
                String originNode = originType == OriginType.NODE ? nonempty(origin, "node", p + ".origin", document) : null;
                if (originNode != null) document.requireNode(nodes, originNode, p + ".origin.node");
                Vec3 offset = Vec3.ZERO;
                if (origin.has("offset")) {
                    var v = vector(origin.get("offset"), 3, true, p + ".origin.offset", document).components();
                    offset = new Vec3(((ConstantValue) v.get(0)).value(), ((ConstantValue) v.get(1)).value(), ((ConstantValue) v.get(2)).value());
                }
                boolean useOriginRotation = origin.has("rotation");
                if (useOriginRotation && !nonempty(origin, "rotation", p + ".origin", document).equals("origin")) throw document.error(p + ".origin.rotation", "must be origin");
                String type = nonempty(action, "type", p + ".action", document);
                Event parsed;
                if (type.equals("commands")) parsed = new Event(new CommandSource(sourceType, sourceNode, attachment), new CommandOrigin(originType, originNode, offset, useOriginRotation), strings(document.requireArray(action, "commands", p + ".action"), p + ".action.commands", document));
                else if (type.equals("external")) parsed = new Event(new CommandSource(sourceType, sourceNode, attachment), new CommandOrigin(originType, originNode, offset, useOriginRotation), List.of(), document.requireIdentifier(nonempty(action, "key", p + ".action", document), p + ".action.key"), document.requireElement(action, "data", p + ".action"));
                else throw document.error(p + ".action.type", "unknown action");
                if (phase.equals("timeline")) {
                    int tick = document.requireTicks(event, "time", p, 0);
                    if (tick > durationTicks) throw document.error(p + ".time", "must be within duration");
                    timeline.add(new TimelineEvent(tick, enumeration(Direction.class, text(event, "direction", "forward", p, document), p + ".direction", document), parsed));
                } else {
                    if (event.has("time") || event.has("direction")) throw document.error(p, "time/direction require timeline phase");
                    events.add(parsed);
                }
            }
            phases.put(phase, events);
        }
        timeline.sort(Comparator.comparingDouble(TimelineEvent::tick));
        return new Events(phases.getOrDefault("start", List.of()), timeline, phases.getOrDefault("loop", List.of()), phases.getOrDefault("stop", List.of()));
    }

    private ScalarValue scalar(JsonElement value, String path, EmoteJsonDocument document) throws EmoteLoadException {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) return new ConstantValue(document.requireFiniteDouble(value, path));
        JsonObject raw = document.requireObject(value, path);
        return new MolangValue(nonempty(raw, "molang", path, document), path + ".molang");
    }
    private ScalarValue delay(JsonObject raw, String key, EmoteJsonDocument document) throws EmoteLoadException {
        if (!raw.has(key)) return new ConstantValue(0);
        if (raw.get(key).isJsonObject()) return scalar(raw.get(key), "$.animation.playback." + key, document);
        return new ConstantValue(document.requireTicks(raw, key, "$.animation.playback", 0));
    }
    private String molang(JsonObject raw, String key, String path, EmoteJsonDocument document) throws EmoteLoadException {
        if (!raw.has(key)) return null;
        return nonempty(raw, key, path, document);
    }
    private CompoundTag compound(String raw, String path, boolean initial, EmoteJsonDocument document) throws EmoteLoadException { return compound(raw, path, initial, document, false); }
    private CompoundTag compound(String raw, String path, boolean initial, EmoteJsonDocument document, boolean patch) throws EmoteLoadException {
        CompoundTag tag;
        try { tag = TagParser.parseCompoundFully(raw); } catch (Exception e) { throw document.error(path, "invalid compound SNBT", e); }
        if (initial || patch) for (String field : tag.keySet()) if (RUNTIME_NBT_FIELDS.contains(field) || initial && CONTENT_FIELDS.contains(field)) throw document.error(path, "runtime-owned field " + field);
        return tag;
    }
    private List<String> strings(JsonArray raw, String path, EmoteJsonDocument document) throws EmoteLoadException {
        if (raw == null) return List.of();
        List<String> result = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            if (document.isNotString(raw.get(i))) throw document.error(path + "[" + i + "]", "must be a string");
            result.add(raw.get(i).getAsString());
        }
        return result;
    }
    private VectorValue optionalVector(JsonObject raw, String key, int componentCount, String path, EmoteJsonDocument document) throws EmoteLoadException { return raw.has(key) ? vector(raw.get(key), componentCount, false, path + "." + key, document) : null; }
    private JsonObject object(JsonObject raw, String key, String path, EmoteJsonDocument document) throws EmoteLoadException { JsonObject o = document.optionalObject(raw, key, path); return o == null ? new JsonObject() : o; }
    private String text(JsonObject raw, String key, String fallback, String path, EmoteJsonDocument document) throws EmoteLoadException { return raw.has(key) ? document.requireString(raw, key, path) : fallback; }
    private String nonempty(JsonObject raw, String key, String path, EmoteJsonDocument document) throws EmoteLoadException { String s = document.requireString(raw, key, path); if (s.isBlank()) throw document.error(path + "." + key, "must not be blank"); return s; }
    private double number(JsonObject raw, String key, String path, EmoteJsonDocument document) throws EmoteLoadException { return document.requireFiniteDouble(document.requireElement(raw, key, path), path + "." + key); }
    private double number(JsonObject raw, String key, double fallback, String path, EmoteJsonDocument document) throws EmoteLoadException { return raw.has(key) ? number(raw, key, path, document) : fallback; }
    private boolean bool(JsonObject raw, String key, boolean fallback, String path, EmoteJsonDocument document) throws EmoteLoadException { return raw.has(key) ? document.requireBoolean(raw, key, path) : fallback; }
    private <E extends Enum<E>> E enumeration(Class<E> type, String raw, String path, EmoteJsonDocument document) throws EmoteLoadException {
        try { return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { throw document.error(path, "unsupported value: " + raw); }
    }
}
