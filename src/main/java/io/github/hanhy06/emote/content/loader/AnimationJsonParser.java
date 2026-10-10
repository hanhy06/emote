package io.github.hanhy06.emote.content.loader;

import com.google.gson.*;
import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.content.LoadedAnimation;
import io.github.hanhy06.emote.skin.model.PlayerSkinPart;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.util.*;

import static io.github.hanhy06.emote.api.animation.EmoteAnimation.*;
import io.github.hanhy06.emote.api.EmoteCallback;
import io.github.hanhy06.emote.api.EmoteLoadException;

public final class AnimationJsonParser {
    private static final Set<String> CONTENT_FIELDS = Set.of("item", "item_display", "block_state", "text");

    public LoadedAnimation parse(Path path) throws EmoteLoadException { return parse(EmoteJsonDocument.read(path)); }
    public LoadedAnimation parse(Path path, byte[] bytes) throws EmoteLoadException { return parse(EmoteJsonDocument.parse(path, bytes)); }

    LoadedAnimation parse(EmoteJsonDocument d) throws EmoteLoadException {
        JsonObject root = d.root();
        if (!d.type().equals("animation")) throw d.error("$.type", "must equal animation");
        d.requireExactInt(root, "schema_version", "$", SCHEMA_VERSION);
        Identifier id = d.requireIdentifier(d.requireString(root, "id", "$"), "$.id");
        Map<String, Node> nodes = nodes(d.requireObject(root, "nodes", "$"), d);
        JsonObject clip = d.requireObject(root, "animation", "$");
        int duration = d.requireTime(clip, "duration", "$.animation", 1);
        if (duration > 12000) throw d.error("$.animation.duration", "must not exceed 12000 ticks");
        JsonObject playback = object(clip, "playback", "$.animation", d);
        LoopMode mode = enumeration(LoopMode.class, text(playback, "mode", "once", "$.animation.playback", d), "$.animation.playback.mode", d);
        int loopStart = playback.has("loop_start") ? d.requireTime(playback, "loop_start", "$.animation.playback", 0) : 0;
        if (loopStart < 0 || loopStart >= duration || mode != LoopMode.LOOP && loopStart != 0) throw d.error("$.animation.playback.loop_start", "invalid loop start");
        ScalarValue startDelay = delay(playback, "start_delay", d), loopDelay = delay(playback, "loop_delay", d);
        JsonObject settings = object(root, "settings", "$", d);
        int cooldown = settings.has("cooldown") ? d.requireTime(settings, "cooldown", "$.settings", 0) : 0;
        double deadzone = number(settings, "rotation_deadzone", 50, "$.settings", d);
        int interpolation = settings.has("display_interpolation_ticks") ? d.requireInt(settings, "display_interpolation_ticks", "$.settings") : 1;
        if (cooldown < 0) throw d.error("$.settings.cooldown", "must not be negative");
        if (deadzone < 0 || deadzone > 180) throw d.error("$.settings.rotation_deadzone", "must be between 0 and 180");
        if (interpolation < 0) throw d.error("$.settings.display_interpolation_ticks", "must not be negative");
        EmotePlayerBehavior behavior = animationPlayer(object(settings, "player", "$.settings", d), d);
        JsonObject programs = object(clip, "programs", "$.animation", d);
        MolangPrograms molang = new MolangPrograms(program(programs, "initialize", "$.animation.programs", d), program(programs, "update", "$.animation.programs", d));
        JsonObject clock = object(clip, "clock", "$.animation", d);
        String clockType = text(clock, "type", "elapsed", "$.animation.clock", d);
        if (!Set.of("elapsed", "molang").contains(clockType)) throw d.error("$.animation.clock.type", "unknown clock");
        String expression = clockType.equals("molang") ? program(clock, "expression", "$.animation.clock", d) : null;
        if (clockType.equals("molang") && expression == null) throw d.error("$.animation.clock.expression", "is required");
        Map<String, Track> tracks = tracks(d.requireArray(clip, "tracks", "$.animation"), nodes, duration, d);
        Events events = events(object(clip, "events", "$.animation", d), nodes, duration, d);
        return new LoadedAnimation(d.sourcePath(), new EmoteAnimation(id,
            parseMetadata(d.requireObject(root, "metadata", "$"), d),
            new Settings(bool(settings, "standalone", true, "$.settings", d), cooldown, (float) deadzone, interpolation, behavior,
                new PlaybackSettings(mode, loopStart, startDelay, loopDelay)),
            molang, nodes, new Timeline(duration, tracks, events, expression), parseCallbacks(root, d),
            root.has("target_minecraft_version") ? d.requireString(root, "target_minecraft_version", "$") : null,
            object(root, "resources", "$", d), object(root, "source", "$", d)));
    }

    static List<EmoteCallback> parseCallbacks(JsonObject root, EmoteJsonDocument document) throws EmoteLoadException {
        JsonArray array = document.optionalArray(root, "callbacks", "$");
        if (array == null) return List.of();
        List<EmoteCallback> callbacks = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            String path = "$.callbacks[" + index + "]";
            JsonObject callback = document.requireObject(array.get(index), path);
            Identifier name = document.requireIdentifier(document.requireString(callback, "name", path), path + ".name");
            String payload = callback.has("payload") ? document.requireString(callback, "payload", path) : "";
            callbacks.add(new EmoteCallback(name, payload));
        }
        return List.copyOf(callbacks);
    }

    static EmoteMetadata parseMetadata(JsonObject object, EmoteJsonDocument document)
        throws EmoteLoadException {
        String name = document.requireString(object, "name", "$.metadata");
        if (name.isBlank()) {
            throw document.error("$.metadata.name", "must not be blank");
        }
        String description = document.requireString(object, "description", "$.metadata");
        LinkedHashMap<String, JsonElement> additional = new LinkedHashMap<>();
        object.entrySet().stream()
            .filter(entry -> !entry.getKey().equals("name") && !entry.getKey().equals("description"))
            .forEach(entry -> additional.put(entry.getKey(), entry.getValue()));
        return new EmoteMetadata(name, description, additional);
    }

    static EmotePlayerBehavior parsePlayer(JsonObject object, String path, EmoteJsonDocument document)
        throws EmoteLoadException {
        boolean hidden = document.requireBoolean(object, "hidden", path);
        JsonObject stopObject = document.requireObject(object, "stop_conditions", path);
        String stopPath = path + ".stop_conditions";
        double movementDistance = document.requireFiniteDouble(
            document.requireElement(stopObject, "movement_distance", stopPath),
            stopPath + ".movement_distance"
        );
        if (movementDistance < 0.0D) {
            throw document.error(stopPath + ".movement_distance", "must not be negative");
        }
        return new EmotePlayerBehavior(hidden, new EmotePlayerBehavior.StopConditions(
            movementDistance,
            document.requireBoolean(stopObject, "jump", stopPath),
            document.requireBoolean(stopObject, "submerge", stopPath),
            document.requireBoolean(stopObject, "ride", stopPath),
            document.requireBoolean(stopObject, "damage", stopPath),
            document.requireBoolean(stopObject, "attack", stopPath),
            document.requireBoolean(stopObject, "game_mode_change", stopPath)
        ));
    }


    private EmotePlayerBehavior animationPlayer(JsonObject player, EmoteJsonDocument d) throws EmoteLoadException {
        JsonObject stop = object(player, "stop_conditions", "$.settings.player", d);
        double distance = number(stop, "movement_distance", 0.3, "$.settings.player.stop_conditions", d);
        if (distance < 0) throw d.error("$.settings.player.stop_conditions.movement_distance", "must not be negative");
        String p = "$.settings.player.stop_conditions";
        return new EmotePlayerBehavior(bool(player, "hidden", true, "$.settings.player", d),
            new EmotePlayerBehavior.StopConditions(distance, bool(stop, "jump", true, p, d), bool(stop, "submerge", true, p, d),
                bool(stop, "ride", true, p, d), bool(stop, "damage", true, p, d), bool(stop, "attack", true, p, d), bool(stop, "game_mode_change", true, p, d)));
    }

    private Map<String, Node> nodes(JsonObject input, EmoteJsonDocument d) throws EmoteLoadException {
        if (input.isEmpty()) throw d.error("$.nodes", "must not be empty");
        Map<String, Node> nodes = new LinkedHashMap<>();
        for (var entry : input.entrySet()) {
            String path = "$.nodes." + entry.getKey();
            if (entry.getKey().isBlank()) throw d.error("$.nodes", "node id must not be blank");
            JsonObject n = d.requireObject(entry.getValue(), path);
            List<Operation> operations = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            JsonArray transforms = d.optionalArray(n, "transform", path);
            if (transforms != null) for (int i = 0; i < transforms.size(); i++) {
                String p = path + ".transform[" + i + "]";
                JsonObject o = d.requireObject(transforms.get(i), p);
                String id = nonempty(o, "id", p, d);
                if (!ids.add(id)) throw d.error(p + ".id", "duplicate operation");
                OperationType op = enumeration(OperationType.class, nonempty(o, "op", p, d), p + ".op", d);
                RotationOrder order = op == OperationType.ROTATE_EULER ? enumeration(RotationOrder.class, nonempty(o, "order", p, d), p + ".order", d) : null;
                int size = op == OperationType.MATRIX ? 16 : op == OperationType.ROTATE_QUATERNION ? 4 : 3;
                List<Double> value = new ArrayList<>();
                VectorValue vector = vector(d.requireElement(o, "value", p), size, true, p + ".value", d);
                for (ScalarValue v : vector.components()) value.add(((ConstantValue) v).value());
                if (size == 4) {
                    double length = Math.sqrt(value.stream().mapToDouble(v -> v * v).sum());
                    if (length == 0 || !Double.isFinite(length)) throw d.error(p + ".value", "invalid quaternion");
                    value.replaceAll(v -> v / length);
                }
                operations.add(new Operation(id, op, order, value));
            }
            JsonObject inherit = object(n, "inherit", path, d);
            Inheritance inheritance = new Inheritance(enumeration(RotationInheritance.class, text(inherit, "rotation", "parent", path + ".inherit", d), path + ".inherit.rotation", d),
                bool(inherit, "scale", true, path + ".inherit", d), bool(inherit, "visibility", true, path + ".inherit", d));
            Map<String, Attachment> attachments = new TreeMap<>();
            for (var a : object(n, "attachments", path, d).entrySet()) {
                if (a.getKey().isBlank()) throw d.error(path + ".attachments", "attachment id must not be blank");
                String p = path + ".attachments." + a.getKey();
                JsonObject definition = d.requireObject(a.getValue(), p);
                String type = nonempty(definition, "type", p, d);
                boolean visible = bool(definition, "visible", true, p, d);
                CompoundTag nbt = definition.has("entity_nbt") ? compound(d.requireString(definition, "entity_nbt", p), p + ".entity_nbt", true, d) : new CompoundTag();
                Attachment attachment = switch (type) {
                    case "item_display" -> new ItemAttachment(visible, nbt, compound(d.requireString(definition, "item_stack_snbt", p), p + ".item_stack_snbt", false, d), nonempty(definition, "item_display", p, d));
                    case "block_display" -> new BlockAttachment(visible, nbt, compound(d.requireString(definition, "block_state_snbt", p), p + ".block_state_snbt", false, d));
                    case "text_display" -> new TextAttachment(visible, nbt, d.requireElement(definition, "text", p));
                    case "player_skin" -> {
                        PlayerSkinPart part = enumeration(PlayerSkinPart.class, nonempty(definition, "part", p, d), p + ".part", d);
                        JsonObject region = d.requireObject(definition, "region", p);
                        double from = number(region, "from", p + ".region", d), to = number(region, "to", p + ".region", d);
                        if (!(from >= 0 && from < to && to <= 1)) throw d.error(p + ".region", "must satisfy 0 <= from < to <= 1");
                        yield new SkinAttachment(visible, part, from, to);
                    }
                    case "external" -> new ExternalAttachment(visible, d.requireIdentifier(nonempty(definition, "key", p, d), p + ".key"), d.requireElement(definition, "data", p));
                    default -> throw d.error(p + ".type", "unknown attachment");
                };
                if (!(attachment instanceof ItemAttachment || attachment instanceof BlockAttachment || attachment instanceof TextAttachment) && definition.has("entity_nbt")) throw d.error(p + ".entity_nbt", "requires a display attachment");
                attachments.put(a.getKey(), attachment);
            }
            nodes.put(entry.getKey(), new Node(n.has("name") ? d.requireString(n, "name", path) : null,
                n.has("parent") ? nonempty(n, "parent", path, d) : null, inheritance, bool(n, "visible", true, path, d),
                operations, attachments, object(n, "source", path, d)));
        }
        Set<String> pending = new TreeSet<>(nodes.keySet());
        Set<String> ready = new HashSet<>();
        while (!pending.isEmpty()) {
            List<String> batch = new ArrayList<>();
            for (String id : pending) {
                String parent = nodes.get(id).parentId();
                if (parent != null && !nodes.containsKey(parent)) throw d.error("$.nodes." + id + ".parent", "unknown parent");
                if (parent == null || ready.contains(parent)) batch.add(id);
            }
            if (batch.isEmpty()) throw d.error("$.nodes." + pending.iterator().next() + ".parent", "parent cycle");
            pending.removeAll(batch); ready.addAll(batch);
        }
        return nodes;
    }

    private Map<String, Track> tracks(JsonArray input, Map<String, Node> nodes, int duration, EmoteJsonDocument d) throws EmoteLoadException {
        Map<String, Track> result = new LinkedHashMap<>();
        Set<List<String>> signatures = new HashSet<>();
        for (int i = 0; i < input.size(); i++) {
            String path = "$.animation.tracks[" + i + "]";
            JsonObject raw = d.requireObject(input.get(i), path), target = d.requireObject(raw, "target", path);
            String nodeId = nonempty(target, "node", path + ".target", d);
            Node node = d.requireNode(nodes, nodeId, path + ".target.node");
            String opId = target.has("operation") ? nonempty(target, "operation", path + ".target", d) : null;
            String aId = target.has("attachment") ? nonempty(target, "attachment", path + ".target", d) : null;
            if (opId != null && aId != null) throw d.error(path + ".target", "cannot target operation and attachment together");
            Operation op = null;
            if (opId != null) {
                for (Operation candidate : node.transform()) if (candidate.id().equals(opId)) op = candidate;
                if (op == null) throw d.error(path + ".target.operation", "unknown operation");
            }
            if (aId != null && !node.attachments().containsKey(aId)) throw d.error(path + ".target.attachment", "unknown attachment");
            Channel channel = enumeration(Channel.class, nonempty(raw, "channel", path, d), path + ".channel", d);
            if (channel == Channel.VALUE && op == null || channel != Channel.VALUE && op != null) throw d.error(path + ".target", "channel does not match target");
            if (channel == Channel.NBT && (aId == null || !(node.attachments().get(aId) instanceof ItemAttachment || node.attachments().get(aId) instanceof BlockAttachment || node.attachments().get(aId) instanceof TextAttachment))) throw d.error(path + ".target", "NBT requires a display attachment");
            Target destination = new Target(nodeId, opId, aId);
            List<String> key = List.of(nodeId, Objects.toString(opId, ""), Objects.toString(aId, ""), channel.name());
            if (!signatures.add(key)) throw d.error(path + ".target", "duplicate target/channel");
            int size = op == null ? 0 : op.op() == OperationType.MATRIX ? 16 : op.op() == OperationType.ROTATE_QUATERNION ? 4 : 3;
            JsonObject driver = d.requireObject(raw, "driver", path);
            String p = path + ".driver";
            DriverType type = enumeration(DriverType.class, nonempty(driver, "type", p, d), p + ".type", d);
            boolean firstPre = text(driver, "before", "base", p, d).equals("first_pre");
            if (!Set.of("base", "first_pre").contains(text(driver, "before", "base", p, d))) throw d.error(p + ".before", "invalid before policy");
            Value expression = null;
            List<Keyframe> keys = new ArrayList<>();
            List<Segment> segments = new ArrayList<>();
            if (type == DriverType.EXPRESSION) {
                if (channel == Channel.NBT || size == 16) throw d.error(p, "requires a state or step curve");
                expression = value(d.requireElement(driver, "value", p), channel, size, p + ".value", d);
            } else {
                if (type == DriverType.CURVE && channel != Channel.VALUE || type == DriverType.STATE && channel == Channel.VALUE) throw d.error(p + ".type", "driver does not match channel");
                JsonArray array = d.requireArray(driver, "keys", p);
                if (array.isEmpty()) throw d.error(p + ".keys", "must not be empty");
                int previous = -1;
                for (int k = 0; k < array.size(); k++) {
                    String kp = p + ".keys[" + k + "]";
                    JsonObject keyframe = d.requireObject(array.get(k), kp);
                    int time = d.requireTime(keyframe, "time", kp, 0);
                    if (time < 0 || time > duration || time < previous || time == previous && channel != Channel.NBT) {
                        throw d.error(kp + ".time", channel == Channel.NBT ? "must not decrease and must be within duration" : "must increase strictly within duration");
                    }
                    previous = time;
                    Value pre, post;
                    if (keyframe.has("value")) {
                        if (keyframe.has("pre") || keyframe.has("post")) throw d.error(kp, "value and pre/post are exclusive");
                        pre = post = value(d.requireElement(keyframe, "value", kp), channel, size, kp + ".value", d);
                    } else {
                        if (type == DriverType.STATE) throw d.error(kp + ".value", "is required");
                        pre = value(d.requireElement(keyframe, "pre", kp), channel, size, kp + ".pre", d);
                        post = value(d.requireElement(keyframe, "post", kp), channel, size, kp + ".post", d);
                    }
                    keys.add(new Keyframe(time, pre, post));
                }
                if (type == DriverType.CURVE) {
                    JsonArray arraySegments = d.requireArray(driver, "segments", p);
                    if (arraySegments.size() != keys.size() - 1) throw d.error(p + ".segments", "must contain keys.length - 1 segments");
                    for (int s = 0; s < arraySegments.size(); s++) segments.add(segment(d.requireObject(arraySegments.get(s), p + ".segments[" + s + "]"), size, p + ".segments[" + s + "]", d));
                }
            }
            result.put(path, new Track(destination, channel, new Driver(type, expression, keys, segments, firstPre)));
        }
        return result;
    }

    private Segment segment(JsonObject raw, int size, String path, EmoteJsonDocument d) throws EmoteLoadException {
        Interpolation interpolation = enumeration(Interpolation.class, nonempty(raw, "interpolation", path, d), path + ".interpolation", d);
        if (size == 16 && interpolation != Interpolation.STEP || size == 4 && interpolation != Interpolation.STEP && interpolation != Interpolation.SLERP || size == 3 && interpolation == Interpolation.SLERP) throw d.error(path + ".interpolation", "does not match value dimension");
        Easing easing = null;
        if (raw.has("easing")) {
            if (interpolation == Interpolation.STEP) throw d.error(path + ".easing", "not allowed for step");
            JsonObject e = d.requireObject(raw, "easing", path);
            String ep = path + ".easing", kernel = nonempty(e, "kernel", ep, d);
            if (!Set.of("linear", "power", "sine", "expo", "circ", "back", "blockbench_elastic", "blockbench_bounce", "steps").contains(kernel)) throw d.error(ep + ".kernel", "unknown easing");
            String direction = kernel.equals("linear") ? "in" : nonempty(e, "direction", ep, d);
            if (!Set.of("in", "out", "in_out").contains(direction)) throw d.error(ep + ".direction", "invalid easing direction");
            double parameter = switch (kernel) {
                case "power" -> number(e, "exponent", ep, d);
                case "back" -> number(e, "overshoot", 1.70158, ep, d);
                case "blockbench_elastic" -> number(e, "frequency", 1, ep, d);
                case "blockbench_bounce" -> number(e, "bounciness", 0.5, ep, d);
                case "steps" -> d.requireInt(e, "count", ep);
                default -> 0;
            };
            if (kernel.equals("power") && parameter <= 0 || kernel.equals("steps") && parameter < 2 || kernel.equals("blockbench_bounce") && !(parameter > 0 && parameter < 1)) throw d.error(ep, "invalid easing parameter");
            if (interpolation == Interpolation.BEZIER && Set.of("back", "blockbench_elastic").contains(kernel)) throw d.error(ep, "overshoot easing is not supported for Bezier");
            easing = new Easing(kernel, direction, parameter);
        }
        List<BezierHandle> handles = new ArrayList<>();
        if (interpolation == Interpolation.BEZIER) {
            JsonArray h = d.requireArray(raw, "handles", path);
            if (h.size() != size) throw d.error(path + ".handles", "must match value dimension");
            for (int i = 0; i < h.size(); i++) {
                String hp = path + ".handles[" + i + "]";
                JsonObject handle = d.requireObject(h.get(i), hp), out = d.requireObject(handle, "out", hp), in = d.requireObject(handle, "in", hp);
                double ot = number(out, "time", hp + ".out", d), it = number(in, "time", hp + ".in", d);
                if (!(ot >= 0 && ot <= it && it <= 1)) throw d.error(hp, "time handles must be monotonic within [0, 1]");
                handles.add(new BezierHandle(ot, scalar(d.requireElement(out, "value", hp + ".out"), hp + ".out.value", d), it, scalar(d.requireElement(in, "value", hp + ".in"), hp + ".in.value", d)));
            }
        }
        VectorValue out = optionalVector(raw, "out_tangent", size, path, d), in = optionalVector(raw, "in_tangent", size, path, d);
        if (interpolation == Interpolation.HERMITE && (out == null || in == null)) throw d.error(path, "Hermite tangents are required");
        return new Segment(interpolation, easing, number(raw, "tension", 0.5, path, d), optionalVector(raw, "previous", size, path, d),
            optionalVector(raw, "following", size, path, d), out, in, handles);
    }

    private Value value(JsonElement raw, Channel channel, int size, String path, EmoteJsonDocument d) throws EmoteLoadException {
        if (channel == Channel.VALUE) return vector(raw, size, size != 3, path, d);
        if (channel == Channel.VISIBLE) {
            if (raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isBoolean()) return new ConstantVisibility(raw.getAsBoolean());
            ScalarValue expression = scalar(raw, path, d);
            if (!(expression instanceof MolangValue m)) throw d.error(path, "visibility requires boolean or Molang");
            return new MolangVisibility(m.source(), m.path());
        }
        JsonObject patch = d.requireObject(raw, path);
        List<String> remove = strings(d.optionalArray(patch, "remove", path), path + ".remove", d);
        for (String field : remove) if (RUNTIME_NBT_FIELDS.contains(field)) throw d.error(path + ".remove", "cannot remove runtime-owned field " + field);
        JsonElement merge = d.requireElement(patch, "merge", path);
        if (merge.isJsonPrimitive() && merge.getAsJsonPrimitive().isString()) return new FixedNbtValue(compound(merge.getAsString(), path + ".merge", false, d, true), remove);
        ScalarValue expression = scalar(merge, path + ".merge", d);
        if (!(expression instanceof MolangValue m)) throw d.error(path + ".merge", "requires SNBT or Molang");
        return new MolangNbtValue(m.source(), m.path(), remove);
    }

    private VectorValue vector(JsonElement raw, int size, boolean literal, String path, EmoteJsonDocument d) throws EmoteLoadException {
        if (!raw.isJsonArray() || raw.getAsJsonArray().size() != size) throw d.error(path, "must contain " + size + " values");
        List<ScalarValue> values = new ArrayList<>();
        for (int i = 0; i < size; i++) values.add(literal ? new ConstantValue(d.requireFiniteDouble(raw.getAsJsonArray().get(i), path + "[" + i + "]")) : scalar(raw.getAsJsonArray().get(i), path + "[" + i + "]", d));
        if (size == 4 && values.stream().allMatch(v -> ((ConstantValue) v).value() == 0)) throw d.error(path, "quaternion must not be zero");
        return new VectorValue(values);
    }

    private Events events(JsonObject input, Map<String, Node> nodes, int duration, EmoteJsonDocument d) throws EmoteLoadException {
        Map<String, List<Event>> phases = new HashMap<>();
        List<TimelineEvent> timeline = new ArrayList<>();
        for (var entry : input.entrySet()) {
            String phase = entry.getKey(), path = "$.animation.events." + phase;
            if (!Set.of("start", "timeline", "loop", "stop").contains(phase)) throw d.error(path, "unknown event phase");
            if (!entry.getValue().isJsonArray()) throw d.error(path, "must be an array");
            List<Event> events = new ArrayList<>();
            JsonArray array = entry.getValue().getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                String p = path + "[" + i + "]";
                JsonObject event = d.requireObject(array.get(i), p), source = d.requireObject(event, "source", p), origin = d.requireObject(event, "origin", p), action = d.requireObject(event, "action", p);
                SourceType st = enumeration(SourceType.class, nonempty(source, "type", p + ".source", d), p + ".source.type", d);
                String sourceNode = st == SourceType.NODE ? nonempty(source, "node", p + ".source", d) : null;
                String attachment = st == SourceType.NODE ? nonempty(source, "attachment", p + ".source", d) : null;
                if (sourceNode != null) {
                    Attachment a = d.requireNode(nodes, sourceNode, p + ".source.node").attachments().get(attachment);
                    if (!(a instanceof ItemAttachment || a instanceof BlockAttachment || a instanceof TextAttachment || a instanceof SkinAttachment)) throw d.error(p + ".source.attachment", "requires an entity attachment");
                }
                OriginType ot = enumeration(OriginType.class, nonempty(origin, "type", p + ".origin", d), p + ".origin.type", d);
                String originNode = ot == OriginType.NODE ? nonempty(origin, "node", p + ".origin", d) : null;
                if (originNode != null) d.requireNode(nodes, originNode, p + ".origin.node");
                Vec3 offset = Vec3.ZERO;
                if (origin.has("offset")) {
                    var v = vector(origin.get("offset"), 3, true, p + ".origin.offset", d).components();
                    offset = new Vec3(((ConstantValue) v.get(0)).value(), ((ConstantValue) v.get(1)).value(), ((ConstantValue) v.get(2)).value());
                }
                String type = nonempty(action, "type", p + ".action", d);
                Event parsed;
                if (type.equals("commands")) parsed = new Event(new CommandSource(st, sourceNode, attachment), new CommandOrigin(ot, originNode, offset), strings(d.requireArray(action, "commands", p + ".action"), p + ".action.commands", d));
                else if (type.equals("external")) parsed = new Event(new CommandSource(st, sourceNode, attachment), new CommandOrigin(ot, originNode, offset), List.of(), d.requireIdentifier(nonempty(action, "key", p + ".action", d), p + ".action.key"), d.requireElement(action, "data", p + ".action"));
                else throw d.error(p + ".action.type", "unknown action");
                if (phase.equals("timeline")) {
                    int time = d.requireTime(event, "time", p, 0);
                    if (time < 0 || time > duration) throw d.error(p + ".time", "must be within duration");
                    timeline.add(new TimelineEvent(time, enumeration(Direction.class, text(event, "direction", "forward", p, d), p + ".direction", d), parsed));
                } else {
                    if (event.has("time") || event.has("direction")) throw d.error(p, "time/direction require timeline phase");
                    events.add(parsed);
                }
            }
            phases.put(phase, events);
        }
        timeline.sort(Comparator.comparingDouble(TimelineEvent::time));
        return new Events(phases.getOrDefault("start", List.of()), timeline, phases.getOrDefault("loop", List.of()), phases.getOrDefault("stop", List.of()));
    }

    private ScalarValue scalar(JsonElement value, String path, EmoteJsonDocument d) throws EmoteLoadException {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) return new ConstantValue(d.requireFiniteDouble(value, path));
        JsonObject raw = d.requireObject(value, path);
        String source = nonempty(raw, "molang", path, d);
        return new MolangValue(source, path + ".molang");
    }
    private ScalarValue delay(JsonObject raw, String key, EmoteJsonDocument d) throws EmoteLoadException {
        if (!raw.has(key)) return new ConstantValue(0);
        if (raw.get(key).isJsonObject()) return scalar(raw.get(key), "$.animation.playback." + key, d);
        return new ConstantValue(d.requireTime(raw, key, "$.animation.playback", 0));
    }
    private String program(JsonObject raw, String key, String path, EmoteJsonDocument d) throws EmoteLoadException {
        if (!raw.has(key)) return null;
        return nonempty(raw, key, path, d);
    }
    private CompoundTag compound(String raw, String path, boolean initial, EmoteJsonDocument d) throws EmoteLoadException { return compound(raw, path, initial, d, false); }
    private CompoundTag compound(String raw, String path, boolean initial, EmoteJsonDocument d, boolean patch) throws EmoteLoadException {
        CompoundTag tag;
        try { tag = TagParser.parseCompoundFully(raw); } catch (Exception e) { throw d.error(path, "invalid compound SNBT", e); }
        if (initial || patch) for (String field : tag.keySet()) if (RUNTIME_NBT_FIELDS.contains(field) || initial && CONTENT_FIELDS.contains(field)) throw d.error(path, "runtime-owned field " + field);
        return tag;
    }
    private List<String> strings(JsonArray raw, String path, EmoteJsonDocument d) throws EmoteLoadException {
        if (raw == null) return List.of();
        List<String> result = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            if (d.isNotString(raw.get(i))) throw d.error(path + "[" + i + "]", "must be a string");
            result.add(raw.get(i).getAsString());
        }
        return result;
    }
    private VectorValue optionalVector(JsonObject raw, String key, int size, String path, EmoteJsonDocument d) throws EmoteLoadException { return raw.has(key) ? vector(raw.get(key), size, size != 3, path + "." + key, d) : null; }
    private JsonObject object(JsonObject raw, String key, String path, EmoteJsonDocument d) throws EmoteLoadException { JsonObject o = d.optionalObject(raw, key, path); return o == null ? new JsonObject() : o; }
    private String text(JsonObject raw, String key, String fallback, String path, EmoteJsonDocument d) throws EmoteLoadException { return raw.has(key) ? d.requireString(raw, key, path) : fallback; }
    private String nonempty(JsonObject raw, String key, String path, EmoteJsonDocument d) throws EmoteLoadException { String s = d.requireString(raw, key, path); if (s.isBlank()) throw d.error(path + "." + key, "must not be blank"); return s; }
    private double number(JsonObject raw, String key, String path, EmoteJsonDocument d) throws EmoteLoadException { return d.requireFiniteDouble(d.requireElement(raw, key, path), path + "." + key); }
    private double number(JsonObject raw, String key, double fallback, String path, EmoteJsonDocument d) throws EmoteLoadException { return raw.has(key) ? number(raw, key, path, d) : fallback; }
    private boolean bool(JsonObject raw, String key, boolean fallback, String path, EmoteJsonDocument d) throws EmoteLoadException { return raw.has(key) ? d.requireBoolean(raw, key, path) : fallback; }
    private <E extends Enum<E>> E enumeration(Class<E> type, String raw, String path, EmoteJsonDocument d) throws EmoteLoadException {
        try { return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { throw d.error(path, "unsupported value: " + raw); }
    }
}
