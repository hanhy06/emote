package io.github.hanhy06.emote.animation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;

import java.util.*;

import static io.github.hanhy06.emote.api.animation.EmoteAnimation.*;

final class TimelineJsonParser {
    Timeline parse(JsonObject object, Map<String, Node> nodes, EmoteJsonReader reader)
        throws EmoteAnimationLoadException {
        int durationTicks = reader.requireTime(object, "duration", "$.timeline", 1);
        Map<String, NodeTracks> tracks = parseTracks(
            reader.requireObject(object, "tracks", "$.timeline"), durationTicks, nodes, reader
        );
        Events events = parseEvents(
            reader.optionalObject(object, "events", "$.timeline"),
            durationTicks,
            nodes,
            reader
        );
        return new Timeline(durationTicks, tracks, List.of(), events);
    }

    private Map<String, NodeTracks> parseTracks(
        JsonObject object,
        int durationTicks,
        Map<String, Node> nodes,
        EmoteJsonReader reader
    ) throws EmoteAnimationLoadException {
        LinkedHashMap<String, NodeTracks> tracks = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String nodeId = entry.getKey();
            String path = "$.timeline.tracks." + nodeId;
            Node node = requireNode(nodes, nodeId, path, reader);
            JsonObject nodeObject = reader.requireObject(entry.getValue(), path);
            List<VectorKeyframe> position = parseVectorTrack(
                reader.optionalArray(nodeObject, "position", path), path + ".position", durationTicks, reader
            );
            List<VectorKeyframe> rotation = parseVectorTrack(
                reader.optionalArray(nodeObject, "rotation", path), path + ".rotation", durationTicks, reader
            );
            List<VectorKeyframe> scale = parseVectorTrack(
                reader.optionalArray(nodeObject, "scale", path), path + ".scale", durationTicks, reader
            );
            List<VisibilityKeyframe> visible = parseVisibilityTrack(
                reader.optionalArray(nodeObject, "visible", path), path + ".visible", durationTicks, reader
            );
            if (node instanceof AnchorNode && !visible.isEmpty()) {
                throw reader.error(path + ".visible", "anchor nodes do not support visible tracks");
            }
            if (position.isEmpty() && rotation.isEmpty() && scale.isEmpty() && visible.isEmpty()) {
                throw reader.error(path, "must contain at least one track");
            }
            tracks.put(nodeId, new NodeTracks(position, rotation, scale, visible));
        }
        return Map.copyOf(tracks);
    }

    private List<VectorKeyframe> parseVectorTrack(
        JsonArray array,
        String path,
        int durationTicks,
        EmoteJsonReader reader
    ) throws EmoteAnimationLoadException {
        if (array == null) {
            return List.of();
        }
        if (array.isEmpty()) {
            throw reader.error(path, "must not be empty");
        }
        List<VectorKeyframe> keyframes = new ArrayList<>();
        int previousTick = -1;
        for (int index = 0; index < array.size(); index++) {
            String keyframePath = path + "[" + index + "]";
            JsonObject object = reader.requireObject(array.get(index), keyframePath);
            int tick = parseTrackTime(object, keyframePath, durationTicks, previousTick, index, reader);
            boolean hasValue = object.has("value") && !object.get("value").isJsonNull();
            boolean hasPre = object.has("pre") && !object.get("pre").isJsonNull();
            boolean hasPost = object.has("post") && !object.get("post").isJsonNull();
            VectorValue pre;
            VectorValue post;
            if (hasValue && !hasPre && !hasPost) {
                pre = parseVectorValue(reader.requireArray(object, "value", keyframePath), keyframePath + ".value", reader);
                post = pre;
            } else if (!hasValue && hasPre && hasPost) {
                pre = parseVectorValue(reader.requireArray(object, "pre", keyframePath), keyframePath + ".pre", reader);
                post = parseVectorValue(reader.requireArray(object, "post", keyframePath), keyframePath + ".post", reader);
            } else {
                throw reader.error(keyframePath, "must define either value or both pre and post");
            }
            boolean last = index == array.size() - 1;
            if (last && (object.has("interpolation") || object.has("easing"))) {
                throw reader.error(keyframePath, "last keyframe must not define interpolation or easing");
            }
            Interpolation interpolation = last ? Interpolation.LINEAR : parseInterpolation(object, keyframePath, reader);
            Easing easing = last ? Easing.LINEAR : parseEasing(object, keyframePath, interpolation, reader);
            keyframes.add(new VectorKeyframe(tick, pre, post, interpolation, easing));
            previousTick = tick;
        }
        return List.copyOf(keyframes);
    }

    private List<VisibilityKeyframe> parseVisibilityTrack(
        JsonArray array,
        String path,
        int durationTicks,
        EmoteJsonReader reader
    ) throws EmoteAnimationLoadException {
        if (array == null) {
            return List.of();
        }
        if (array.isEmpty()) {
            throw reader.error(path, "must not be empty");
        }
        List<VisibilityKeyframe> keyframes = new ArrayList<>();
        int previousTick = -1;
        for (int index = 0; index < array.size(); index++) {
            String keyframePath = path + "[" + index + "]";
            JsonObject object = reader.requireObject(array.get(index), keyframePath);
            int tick = parseTrackTime(object, keyframePath, durationTicks, previousTick, index, reader);
            JsonElement value = reader.requireElement(object, "value", keyframePath);
            VisibilityValue parsed;
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
                parsed = new ConstantVisibility(value.getAsBoolean());
            } else if (!reader.isNotString(value) && !value.getAsString().isBlank()) {
                String source = value.getAsString();
                AnimationJsonLoader.compileMolang(source, keyframePath + ".value", reader);
                parsed = new MolangVisibility(source, keyframePath + ".value");
            } else {
                throw reader.error(keyframePath + ".value", "must be a boolean or Molang string");
            }
            if (object.has("interpolation") || object.has("easing") || object.has("pre") || object.has("post")) {
                throw reader.error(keyframePath, "visible keyframes only support time and value");
            }
            keyframes.add(new VisibilityKeyframe(tick, parsed));
            previousTick = tick;
        }
        return List.copyOf(keyframes);
    }

    private int parseTrackTime(
        JsonObject object,
        String path,
        int durationTicks,
        int previousTick,
        int index,
        EmoteJsonReader reader
    ) throws EmoteAnimationLoadException {
        int tick = reader.requireTime(object, "time", path, 0);
        if (tick < 0 || tick > durationTicks) {
            throw reader.error(path + ".time", "must be between 0t and timeline duration");
        }
        if (index == 0 && tick != 0) {
            throw reader.error(path + ".time", "first keyframe must be at 0t");
        }
        if (tick <= previousTick) {
            throw reader.error(path + ".time", "keyframes must be strictly ordered by time");
        }
        return tick;
    }

    private VectorValue parseVectorValue(JsonArray array, String path, EmoteJsonReader reader)
        throws EmoteAnimationLoadException {
        if (array.size() != 3) {
            throw reader.error(path, "must contain 3 values");
        }
        return new VectorValue(
            parseScalar(array.get(0), path + "[0]", reader),
            parseScalar(array.get(1), path + "[1]", reader),
            parseScalar(array.get(2), path + "[2]", reader)
        );
    }

    private ScalarValue parseScalar(JsonElement element, String path, EmoteJsonReader reader)
        throws EmoteAnimationLoadException {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            return new ConstantValue(reader.requireFiniteDouble(element, path));
        }
        if (!reader.isNotString(element) && !element.getAsString().isBlank()) {
            String source = element.getAsString();
            AnimationJsonLoader.compileMolang(source, path, reader);
            return new MolangValue(source, path);
        }
        throw reader.error(path, "must be a finite number or Molang string");
    }

    private Interpolation parseInterpolation(JsonObject object, String path, EmoteJsonReader reader)
        throws EmoteAnimationLoadException {
        if (!object.has("interpolation")) {
            return Interpolation.LINEAR;
        }
        return switch (reader.requireString(object, "interpolation", path)) {
            case "step" -> Interpolation.STEP;
            case "linear" -> Interpolation.LINEAR;
            default -> throw reader.error(path + ".interpolation", "must be step or linear");
        };
    }

    private Easing parseEasing(JsonObject object, String path, Interpolation interpolation, EmoteJsonReader reader)
        throws EmoteAnimationLoadException {
        if (!object.has("easing")) {
            return Easing.LINEAR;
        }
        if (interpolation == Interpolation.STEP) {
            throw reader.error(path + ".easing", "is not supported by step interpolation");
        }
        String value = reader.requireString(object, "easing", path);
        try {
            return Easing.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw reader.error(path + ".easing", "unsupported easing: " + value);
        }
    }

    private Events parseEvents(
        JsonObject object,
        int durationTicks,
        Map<String, Node> nodes,
        EmoteJsonReader reader
    )
        throws EmoteAnimationLoadException {
        if (object == null) {
            return Events.empty();
        }
        return new Events(
            parseEventArray(
                reader.optionalArray(object, "start", "$.timeline.events"),
                "$.timeline.events.start",
                nodes,
                reader
            ),
            parseTimelineEventArray(
                reader.optionalArray(object, "timeline", "$.timeline.events"),
                durationTicks,
                nodes,
                reader
            ),
            parseEventArray(
                reader.optionalArray(object, "loop", "$.timeline.events"),
                "$.timeline.events.loop",
                nodes,
                reader
            ),
            parseEventArray(
                reader.optionalArray(object, "stop", "$.timeline.events"),
                "$.timeline.events.stop",
                nodes,
                reader
            )
        );
    }

    private List<Event> parseEventArray(
        JsonArray array,
        String path,
        Map<String, Node> nodes,
        EmoteJsonReader reader
    )
        throws EmoteAnimationLoadException {
        if (array == null) {
            return List.of();
        }
        List<Event> events = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            String eventPath = path + "[" + index + "]";
            events.add(parseEvent(reader.requireObject(array.get(index), eventPath), eventPath, nodes, reader));
        }
        return List.copyOf(events);
    }

    private List<TimelineEvent> parseTimelineEventArray(
        JsonArray array,
        int durationTicks,
        Map<String, Node> nodes,
        EmoteJsonReader reader
    ) throws EmoteAnimationLoadException {
        if (array == null) {
            return List.of();
        }
        List<TimelineEvent> events = new ArrayList<>();
        int previousTick = -1;
        for (int index = 0; index < array.size(); index++) {
            String path = "$.timeline.events.timeline[" + index + "]";
            JsonObject object = reader.requireObject(array.get(index), path);
            int tick = reader.requireTime(object, "time", path, 0);
            if (tick < 0 || tick >= durationTicks) {
                throw reader.error(path + ".time", "must be before timeline duration");
            }
            if (tick < previousTick) {
                throw reader.error(path + ".time", "timeline events must be ordered by time");
            }
            previousTick = tick;
            Event event = parseEvent(object, path, nodes, reader);
            events.add(new TimelineEvent(tick, event.source(), event.origin(), event.commands()));
        }
        return List.copyOf(events);
    }

    private Event parseEvent(
        JsonObject object,
        String path,
        Map<String, Node> nodes,
        EmoteJsonReader reader
    )
        throws EmoteAnimationLoadException {
        CommandSource source = parseCommandSource(
            reader.requireObject(object, "source", path),
            path + ".source",
            nodes,
            reader
        );
        CommandOrigin origin = parseCommandOrigin(
            reader.requireObject(object, "origin", path),
            path + ".origin",
            nodes,
            reader
        );
        JsonArray commandArray = reader.requireArray(object, "commands", path);
        List<String> commands = new ArrayList<>();
        for (int index = 0; index < commandArray.size(); index++) {
            String commandPath = path + ".commands[" + index + "]";
            JsonElement element = commandArray.get(index);
            if (reader.isNotString(element)) {
                throw reader.error(commandPath, "must be a string");
            }
            String command = element.getAsString();
            if (command.isBlank()) {
                throw reader.error(commandPath, "must not be blank");
            }
            if (command.startsWith("/")) {
                throw reader.error(commandPath, "must not start with /");
            }
            commands.add(command);
        }
        return new Event(source, origin, commands);
    }

    private CommandSource parseCommandSource(
        JsonObject object,
        String path,
        Map<String, Node> nodes,
        EmoteJsonReader reader
    )
        throws EmoteAnimationLoadException {
        String type = reader.requireString(object, "type", path);
        return switch (type) {
            case "player" -> new CommandSource(SourceType.PLAYER, null);
            case "server" -> new CommandSource(SourceType.SERVER, null);
            case "node" -> {
                String nodeId = reader.requireString(object, "node", path);
                Node node = requireNode(nodes, nodeId, path + ".node", reader);
                if (node instanceof AnchorNode) {
                    throw reader.error(path + ".node", "anchor nodes cannot be command sources");
                }
                yield new CommandSource(SourceType.NODE, nodeId);
            }
            default -> throw reader.error(path + ".type", "unsupported source type: " + type);
        };
    }

    private CommandOrigin parseCommandOrigin(
        JsonObject object,
        String path,
        Map<String, Node> nodes,
        EmoteJsonReader reader
    )
        throws EmoteAnimationLoadException {
        String type = reader.requireString(object, "type", path);
        Vec3 offset = parseOffset(reader.optionalArray(object, "offset", path), path + ".offset", reader);
        return switch (type) {
            case "root" -> new CommandOrigin(OriginType.ROOT, null, offset);
            case "node" -> {
                String nodeId = reader.requireString(object, "node", path);
                requireNode(nodes, nodeId, path + ".node", reader);
                yield new CommandOrigin(OriginType.NODE, nodeId, offset);
            }
            default -> throw reader.error(path + ".type", "unsupported origin type: " + type);
        };
    }

    private Vec3 parseOffset(JsonArray array, String path, EmoteJsonReader reader)
        throws EmoteAnimationLoadException {
        if (array == null) {
            return Vec3.ZERO;
        }
        if (array.size() != 3) {
            throw reader.error(path, "must contain 3 values");
        }
        return new Vec3(
            reader.requireFiniteDouble(array.get(0), path + "[0]"),
            reader.requireFiniteDouble(array.get(1), path + "[1]"),
            reader.requireFiniteDouble(array.get(2), path + "[2]")
        );
    }

    private Node requireNode(
        Map<String, Node> nodes,
        String nodeId,
        String path,
        EmoteJsonReader reader
    )
        throws EmoteAnimationLoadException {
        Node node = nodes.get(nodeId);
        if (node == null) {
            throw reader.error(path, "references unknown node: " + nodeId);
        }
        return node;
    }

    private int optionalInterpolationDuration(
        JsonObject object,
        String path,
        int defaultValue,
        EmoteJsonReader reader
    ) throws EmoteAnimationLoadException {
        String key = "interpolation_duration";
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return defaultValue;
        }
        return reader.requireTime(object, key, path, 0);
    }
}
