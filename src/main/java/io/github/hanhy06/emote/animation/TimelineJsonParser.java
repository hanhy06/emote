package io.github.hanhy06.emote.animation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;

import java.util.*;

import static io.github.hanhy06.emote.api.animation.EmoteAnimation.*;

final class TimelineJsonParser {
    Timeline parse(JsonObject object, Map<String, Node> nodes, AnimationJsonReader reader)
        throws EmoteAnimationLoadException {
        int durationTicks = reader.requireInt(object, "duration_ticks", "$.timeline");
        if (durationTicks <= 0) {
            throw reader.error("$.timeline.duration_ticks", "must be greater than zero");
        }
        String loopText = reader.requireString(object, "loop", "$.timeline");
        LoopMode loop = switch (loopText) {
            case "once" -> LoopMode.ONCE;
            case "loop" -> LoopMode.LOOP;
            case "server_sync" -> LoopMode.SERVER_SYNC;
            default -> throw reader.error("$.timeline.loop", "unsupported loop mode: " + loopText);
        };
        int loopDelayTicks = reader.requireInt(object, "loop_delay_ticks", "$.timeline");
        if (loopDelayTicks < 0) {
            throw reader.error("$.timeline.loop_delay_ticks", "must not be negative");
        }
        if (loop == LoopMode.ONCE && loopDelayTicks != 0) {
            throw reader.error("$.timeline.loop_delay_ticks", "must be zero when loop is once");
        }
        List<Keyframe> keyframes = parseKeyframes(
            reader.requireArray(object, "keyframes", "$.timeline"),
            durationTicks,
            nodes,
            reader
        );
        Events events = parseEvents(
            reader.optionalObject(object, "events", "$.timeline"),
            durationTicks,
            nodes,
            reader
        );
        return new Timeline(durationTicks, loop, loopDelayTicks, keyframes, events);
    }

    private List<Keyframe> parseKeyframes(
        JsonArray array,
        int durationTicks,
        Map<String, Node> nodes,
        AnimationJsonReader reader
    ) throws EmoteAnimationLoadException {
        List<Keyframe> keyframes = new ArrayList<>();
        Map<String, Integer> previousTransformTicks = new HashMap<>();
        int previousTick = -1;
        for (int index = 0; index < array.size(); index++) {
            String path = "$.timeline.keyframes[" + index + "]";
            JsonObject object = reader.requireObject(array.get(index), path);
            int tick = reader.requireInt(object, "tick", path);
            if (tick < 0 || tick > durationTicks) {
                throw reader.error(path + ".tick", "must be between 0 and duration_ticks");
            }
            if (tick <= previousTick) {
                throw reader.error(path + ".tick", "keyframes must be strictly ordered by tick");
            }
            previousTick = tick;
            int defaultInterpolation = optionalInterpolationDuration(object, path, 0, reader);
            if (defaultInterpolation < 0) {
                throw reader.error(path + ".interpolation_duration_ticks", "must not be negative");
            }
            Map<String, NodeTransform> transforms = parseNodeTransforms(
                reader.optionalObject(object, "node_transforms", path),
                path,
                tick,
                defaultInterpolation,
                previousTransformTicks,
                nodes,
                reader
            );
            Map<String, NodeState> states = parseNodeStates(
                reader.optionalObject(object, "node_states", path),
                path,
                nodes,
                reader
            );
            keyframes.add(new Keyframe(tick, transforms, states));
        }
        return List.copyOf(keyframes);
    }

    private Map<String, NodeTransform> parseNodeTransforms(
        JsonObject object,
        String keyframePath,
        int tick,
        int defaultInterpolation,
        Map<String, Integer> previousTransformTicks,
        Map<String, Node> nodes,
        AnimationJsonReader reader
    ) throws EmoteAnimationLoadException {
        if (object == null) {
            return Map.of();
        }
        LinkedHashMap<String, NodeTransform> transforms = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String nodeId = entry.getKey();
            String path = keyframePath + ".node_transforms." + nodeId;
            requireNode(nodes, nodeId, path, reader);
            JsonObject transform = reader.requireObject(entry.getValue(), path);
            int interpolation = optionalInterpolationDuration(
                transform,
                path,
                defaultInterpolation,
                reader
            );
            int previousTransformTick = previousTransformTicks.getOrDefault(nodeId, 0);
            if (interpolation < 0 || interpolation > tick - previousTransformTick) {
                throw reader.error(
                    path + ".interpolation_duration_ticks",
                    "must fit between the previous transform tick and the current tick"
                );
            }
            Matrix matrix = reader.requireMatrix(transform, "matrix", path);
            if (!(nodes.get(nodeId) instanceof AnchorNode)) {
                matrix = MatrixNormalizer.stabilize(matrix);
            }
            transforms.put(nodeId, new NodeTransform(matrix, interpolation));
            previousTransformTicks.put(nodeId, tick);
        }
        return Map.copyOf(transforms);
    }

    private Map<String, NodeState> parseNodeStates(
        JsonObject object,
        String keyframePath,
        Map<String, Node> nodes,
        AnimationJsonReader reader
    ) throws EmoteAnimationLoadException {
        if (object == null) {
            return Map.of();
        }
        LinkedHashMap<String, NodeState> states = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String nodeId = entry.getKey();
            String path = keyframePath + ".node_states." + nodeId;
            Node node = requireNode(nodes, nodeId, path, reader);
            if (node instanceof AnchorNode) {
                throw reader.error(path, "anchor nodes do not support visible state");
            }
            JsonObject state = reader.requireObject(entry.getValue(), path);
            states.put(nodeId, new NodeState(reader.requireBoolean(state, "visible", path)));
        }
        return Map.copyOf(states);
    }

    private Events parseEvents(
        JsonObject object,
        int durationTicks,
        Map<String, Node> nodes,
        AnimationJsonReader reader
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
        AnimationJsonReader reader
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
        AnimationJsonReader reader
    ) throws EmoteAnimationLoadException {
        if (array == null) {
            return List.of();
        }
        List<TimelineEvent> events = new ArrayList<>();
        int previousTick = -1;
        for (int index = 0; index < array.size(); index++) {
            String path = "$.timeline.events.timeline[" + index + "]";
            JsonObject object = reader.requireObject(array.get(index), path);
            int tick = reader.requireInt(object, "tick", path);
            if (tick < 0 || tick >= durationTicks) {
                throw reader.error(path + ".tick", "must be between 0 and duration_ticks - 1");
            }
            if (tick < previousTick) {
                throw reader.error(path + ".tick", "timeline events must be ordered by tick");
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
        AnimationJsonReader reader
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
        AnimationJsonReader reader
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
        AnimationJsonReader reader
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

    private Vec3 parseOffset(JsonArray array, String path, AnimationJsonReader reader)
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
        AnimationJsonReader reader
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
        AnimationJsonReader reader
    ) throws EmoteAnimationLoadException {
        String key = "interpolation_duration_ticks";
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return defaultValue;
        }
        return reader.requireInt(object, key, path);
    }
}
