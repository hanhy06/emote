package io.github.hanhy06.emote.animation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.hanhy06.emote.animation.EmoteAnimation.*;
import static io.github.hanhy06.emote.animation.EmoteAnimationJsonLoader.*;

final class EmoteTimelineJsonParser {
    Timeline parse(JsonObject object, Map<String, Node> nodes, Path sourcePath)
        throws EmoteAnimationLoadException {
        int durationTicks = requireInt(object, "duration_ticks", "$.timeline", sourcePath);
        if (durationTicks <= 0) {
            throw error(sourcePath, "$.timeline.duration_ticks", "must be greater than zero");
        }
        String loopText = requireString(object, "loop", "$.timeline", sourcePath);
        LoopMode loop = switch (loopText) {
            case "once" -> LoopMode.ONCE;
            case "loop" -> LoopMode.LOOP;
            default -> throw error(sourcePath, "$.timeline.loop", "unsupported loop mode: " + loopText);
        };
        int loopDelayTicks = requireInt(object, "loop_delay_ticks", "$.timeline", sourcePath);
        if (loopDelayTicks < 0) {
            throw error(sourcePath, "$.timeline.loop_delay_ticks", "must not be negative");
        }
        if (loop == LoopMode.ONCE && loopDelayTicks != 0) {
            throw error(sourcePath, "$.timeline.loop_delay_ticks", "must be zero when loop is once");
        }
        List<Keyframe> keyframes = parseKeyframes(
            requireArray(object, "keyframes", "$.timeline", sourcePath),
            durationTicks,
            nodes,
            sourcePath
        );
        Events events = parseEvents(optionalObject(object, "events", "$.timeline", sourcePath), durationTicks, nodes, sourcePath);
        return new Timeline(durationTicks, loop, loopDelayTicks, keyframes, events);
    }

    private List<Keyframe> parseKeyframes(
        JsonArray array,
        int durationTicks,
        Map<String, Node> nodes,
        Path sourcePath
    ) throws EmoteAnimationLoadException {
        List<Keyframe> keyframes = new ArrayList<>();
        Map<String, Integer> previousTransformTicks = new HashMap<>();
        int previousTick = -1;
        for (int index = 0; index < array.size(); index++) {
            String path = "$.timeline.keyframes[" + index + "]";
            JsonObject object = requireObject(array.get(index), path, sourcePath);
            int tick = requireInt(object, "tick", path, sourcePath);
            if (tick < 0 || tick > durationTicks) {
                throw error(sourcePath, path + ".tick", "must be between 0 and duration_ticks");
            }
            if (tick <= previousTick) {
                throw error(sourcePath, path + ".tick", "keyframes must be strictly ordered by tick");
            }
            previousTick = tick;
            int defaultInterpolation = optionalInt(object, "interpolation_duration_ticks", path, 0, sourcePath);
            if (defaultInterpolation < 0) {
                throw error(sourcePath, path + ".interpolation_duration_ticks", "must not be negative");
            }
            Map<String, NodeTransform> transforms = parseNodeTransforms(
                optionalObject(object, "node_transforms", path, sourcePath),
                path,
                tick,
                defaultInterpolation,
                previousTransformTicks,
                nodes,
                sourcePath
            );
            Map<String, NodeState> states = parseNodeStates(
                optionalObject(object, "node_states", path, sourcePath),
                path,
                nodes,
                sourcePath
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
        Path sourcePath
    ) throws EmoteAnimationLoadException {
        if (object == null) {
            return Map.of();
        }
        LinkedHashMap<String, NodeTransform> transforms = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String nodeId = entry.getKey();
            String path = keyframePath + ".node_transforms." + nodeId;
            requireNode(nodes, nodeId, path, sourcePath);
            JsonObject transform = requireObject(entry.getValue(), path, sourcePath);
            int interpolation = optionalInt(
                transform,
                "interpolation_duration_ticks",
                path,
                defaultInterpolation,
                sourcePath
            );
            int previousTransformTick = previousTransformTicks.getOrDefault(nodeId, 0);
            if (interpolation < 0 || interpolation > tick - previousTransformTick) {
                throw error(
                    sourcePath,
                    path + ".interpolation_duration_ticks",
                    "must fit between the previous transform tick and the current tick"
                );
            }
            transforms.put(nodeId, new NodeTransform(
                parseMatrix(requireArray(transform, "matrix", path, sourcePath), path + ".matrix", sourcePath),
                interpolation
            ));
            previousTransformTicks.put(nodeId, tick);
        }
        return Collections.unmodifiableMap(transforms);
    }

    private Map<String, NodeState> parseNodeStates(
        JsonObject object,
        String keyframePath,
        Map<String, Node> nodes,
        Path sourcePath
    ) throws EmoteAnimationLoadException {
        if (object == null) {
            return Map.of();
        }
        LinkedHashMap<String, NodeState> states = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String nodeId = entry.getKey();
            String path = keyframePath + ".node_states." + nodeId;
            Node node = requireNode(nodes, nodeId, path, sourcePath);
            if (node instanceof AnchorNode) {
                throw error(sourcePath, path, "anchor nodes do not support visible state");
            }
            JsonObject state = requireObject(entry.getValue(), path, sourcePath);
            states.put(nodeId, new NodeState(requireBoolean(state, "visible", path, sourcePath)));
        }
        return Collections.unmodifiableMap(states);
    }

    private Events parseEvents(JsonObject object, int durationTicks, Map<String, Node> nodes, Path sourcePath)
        throws EmoteAnimationLoadException {
        if (object == null) {
            return Events.empty();
        }
        return new Events(
            parseEventArray(optionalArray(object, "start", "$.timeline.events", sourcePath), "$.timeline.events.start", nodes, sourcePath),
            parseTimelineEventArray(optionalArray(object, "timeline", "$.timeline.events", sourcePath), durationTicks, nodes, sourcePath),
            parseEventArray(optionalArray(object, "loop", "$.timeline.events", sourcePath), "$.timeline.events.loop", nodes, sourcePath),
            parseEventArray(optionalArray(object, "stop", "$.timeline.events", sourcePath), "$.timeline.events.stop", nodes, sourcePath)
        );
    }

    private List<Event> parseEventArray(JsonArray array, String path, Map<String, Node> nodes, Path sourcePath)
        throws EmoteAnimationLoadException {
        if (array == null) {
            return List.of();
        }
        List<Event> events = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            String eventPath = path + "[" + index + "]";
            events.add(parseEvent(requireObject(array.get(index), eventPath, sourcePath), eventPath, nodes, sourcePath));
        }
        return List.copyOf(events);
    }

    private List<TimelineEvent> parseTimelineEventArray(
        JsonArray array,
        int durationTicks,
        Map<String, Node> nodes,
        Path sourcePath
    ) throws EmoteAnimationLoadException {
        if (array == null) {
            return List.of();
        }
        List<TimelineEvent> events = new ArrayList<>();
        int previousTick = -1;
        for (int index = 0; index < array.size(); index++) {
            String path = "$.timeline.events.timeline[" + index + "]";
            JsonObject object = requireObject(array.get(index), path, sourcePath);
            int tick = requireInt(object, "tick", path, sourcePath);
            if (tick < 0 || tick >= durationTicks) {
                throw error(sourcePath, path + ".tick", "must be between 0 and duration_ticks - 1");
            }
            if (tick < previousTick) {
                throw error(sourcePath, path + ".tick", "timeline events must be ordered by tick");
            }
            previousTick = tick;
            Event event = parseEvent(object, path, nodes, sourcePath);
            events.add(new TimelineEvent(tick, event.source(), event.origin(), event.commands()));
        }
        return List.copyOf(events);
    }

    private Event parseEvent(JsonObject object, String path, Map<String, Node> nodes, Path sourcePath)
        throws EmoteAnimationLoadException {
        CommandSource source = parseCommandSource(requireObject(object, "source", path, sourcePath), path + ".source", nodes, sourcePath);
        CommandOrigin origin = parseCommandOrigin(requireObject(object, "origin", path, sourcePath), path + ".origin", nodes, sourcePath);
        JsonArray commandArray = requireArray(object, "commands", path, sourcePath);
        List<String> commands = new ArrayList<>();
        for (int index = 0; index < commandArray.size(); index++) {
            String commandPath = path + ".commands[" + index + "]";
            JsonElement element = commandArray.get(index);
            if (!isString(element)) {
                throw error(sourcePath, commandPath, "must be a string");
            }
            String command = element.getAsString();
            if (command.isBlank()) {
                throw error(sourcePath, commandPath, "must not be blank");
            }
            if (command.startsWith("/")) {
                throw error(sourcePath, commandPath, "must not start with /");
            }
            commands.add(command);
        }
        return new Event(source, origin, commands);
    }

    private CommandSource parseCommandSource(JsonObject object, String path, Map<String, Node> nodes, Path sourcePath)
        throws EmoteAnimationLoadException {
        String type = requireString(object, "type", path, sourcePath);
        return switch (type) {
            case "player" -> new CommandSource(SourceType.PLAYER, null);
            case "server" -> new CommandSource(SourceType.SERVER, null);
            case "node" -> {
                String nodeId = requireString(object, "node", path, sourcePath);
                Node node = requireNode(nodes, nodeId, path + ".node", sourcePath);
                if (node instanceof AnchorNode) {
                    throw error(sourcePath, path + ".node", "anchor nodes cannot be command sources");
                }
                yield new CommandSource(SourceType.NODE, nodeId);
            }
            default -> throw error(sourcePath, path + ".type", "unsupported source type: " + type);
        };
    }

    private CommandOrigin parseCommandOrigin(JsonObject object, String path, Map<String, Node> nodes, Path sourcePath)
        throws EmoteAnimationLoadException {
        String type = requireString(object, "type", path, sourcePath);
        Vec3 offset = parseOffset(optionalArray(object, "offset", path, sourcePath), path + ".offset", sourcePath);
        return switch (type) {
            case "root" -> new CommandOrigin(OriginType.ROOT, null, offset);
            case "node" -> {
                String nodeId = requireString(object, "node", path, sourcePath);
                requireNode(nodes, nodeId, path + ".node", sourcePath);
                yield new CommandOrigin(OriginType.NODE, nodeId, offset);
            }
            default -> throw error(sourcePath, path + ".type", "unsupported origin type: " + type);
        };
    }

    private Vec3 parseOffset(JsonArray array, String path, Path sourcePath) throws EmoteAnimationLoadException {
        if (array == null) {
            return Vec3.ZERO;
        }
        if (array.size() != 3) {
            throw error(sourcePath, path, "must contain 3 values");
        }
        return new Vec3(
            requireFiniteDouble(array.get(0), path + "[0]", sourcePath),
            requireFiniteDouble(array.get(1), path + "[1]", sourcePath),
            requireFiniteDouble(array.get(2), path + "[2]", sourcePath)
        );
    }

    private Node requireNode(Map<String, Node> nodes, String nodeId, String path, Path sourcePath)
        throws EmoteAnimationLoadException {
        Node node = nodes.get(nodeId);
        if (node == null) {
            throw error(sourcePath, path, "references unknown node: " + nodeId);
        }
        return node;
    }
}
