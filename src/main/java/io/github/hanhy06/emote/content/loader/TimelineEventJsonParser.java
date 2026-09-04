package io.github.hanhy06.emote.content.loader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.hanhy06.emote.api.animation.EmoteAnimation.*;

final class TimelineEventJsonParser {
    Events parse(
        JsonObject object,
        int durationTicks,
        Map<String, Node> nodes,
        EmoteJsonDocument reader
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
        EmoteJsonDocument reader
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
        EmoteJsonDocument reader
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
            events.add(new TimelineEvent(tick, event.source(), event.origin(), event.commands(), event.callbacks()));
        }
        return List.copyOf(events);
    }

    private Event parseEvent(
        JsonObject object,
        String path,
        Map<String, Node> nodes,
        EmoteJsonDocument reader
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
        JsonArray callbackArray = reader.optionalArray(object, "callbacks", path);
        List<Callback> callbacks = new ArrayList<>();
        if (callbackArray != null) {
            for (int index = 0; index < callbackArray.size(); index++) {
                String callbackPath = path + ".callbacks[" + index + "]";
                JsonObject callback = reader.requireObject(callbackArray.get(index), callbackPath);
                String nameValue = reader.requireString(callback, "name", callbackPath);
                Identifier name = Identifier.tryParse(nameValue);
                if (name == null) throw reader.error(callbackPath + ".name", "must be a valid namespaced identifier");
                String payload = callback.has("payload") ? reader.requireString(callback, "payload", callbackPath) : "";
                callbacks.add(new Callback(name, payload));
            }
        }
        return new Event(source, origin, commands, callbacks);
    }

    private CommandSource parseCommandSource(
        JsonObject object,
        String path,
        Map<String, Node> nodes,
        EmoteJsonDocument reader
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
        EmoteJsonDocument reader
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

    private Vec3 parseOffset(JsonArray array, String path, EmoteJsonDocument reader)
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
        EmoteJsonDocument reader
    ) throws EmoteAnimationLoadException {
        Node node = nodes.get(nodeId);
        if (node == null) {
            throw reader.error(path, "references unknown node: " + nodeId);
        }
        return node;
    }
}
