package io.github.hanhy06.emote.content.loader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;

import java.util.*;

import static io.github.hanhy06.emote.api.animation.EmoteAnimation.*;

final class TimelineJsonParser {
    private static final Set<String> RUNTIME_OWNED_NBT_FIELDS = Set.of(
        "id", "UUID", "Pos", "Motion", "Rotation", "Tags", "Passengers",
        "transformation", "interpolation_duration", "start_interpolation", "teleport_duration"
    );
    private final TimelineEventJsonParser eventParser = new TimelineEventJsonParser();

    Timeline parse(JsonObject object, Map<String, Node> nodes, EmoteJsonDocument document)
        throws EmoteAnimationLoadException {
        int durationTicks = document.requireTime(object, "duration", "$.timeline", 1);
        Map<String, NodeTracks> tracks = parseTracks(
            document.requireObject(object, "tracks", "$.timeline"), durationTicks, nodes, document
        );
        Events events = this.eventParser.parse(
            document.optionalObject(object, "events", "$.timeline"),
            durationTicks,
            nodes,
            document
        );
        return new Timeline(durationTicks, tracks, events);
    }

    private Map<String, NodeTracks> parseTracks(
        JsonObject object,
        int durationTicks,
        Map<String, Node> nodes,
        EmoteJsonDocument document
    ) throws EmoteAnimationLoadException {
        LinkedHashMap<String, NodeTracks> tracks = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String nodeId = entry.getKey();
            String path = "$.timeline.tracks." + nodeId;
            Node node = requireNode(nodes, nodeId, path, document);
            JsonObject nodeObject = document.requireObject(entry.getValue(), path);
            List<VectorKeyframe> position = parseVectorTrack(
                document.optionalArray(nodeObject, "position", path), path + ".position", durationTicks, document
            );
            List<VectorKeyframe> rotation = parseVectorTrack(
                document.optionalArray(nodeObject, "rotation", path), path + ".rotation", durationTicks, document
            );
            List<VectorKeyframe> scale = parseVectorTrack(
                document.optionalArray(nodeObject, "scale", path), path + ".scale", durationTicks, document
            );
            List<VisibilityKeyframe> visible = parseVisibilityTrack(
                document.optionalArray(nodeObject, "visible", path), path + ".visible", durationTicks, document
            );
            List<NbtKeyframe> nbt = parseNbtTrack(
                document.optionalArray(nodeObject, "nbt", path), path + ".nbt", durationTicks, document
            );
            if (node instanceof AnchorNode && !visible.isEmpty()) {
                throw document.error(path + ".visible", "anchor nodes do not support visible tracks");
            }
            if (node instanceof AnchorNode && !nbt.isEmpty()) {
                throw document.error(path + ".nbt", "anchor nodes do not support nbt tracks");
            }
            if (node instanceof ItemNode item && item.itemSource() instanceof ParticipantHandItemSource
                && nbt.stream().flatMap(frame -> frame.value().options().stream()).anyMatch(option -> option.contains("item"))) {
                throw document.error(path + ".nbt", "participant hand item nodes do not support item changes in nbt tracks");
            }
            if (position.isEmpty() && rotation.isEmpty() && scale.isEmpty() && visible.isEmpty() && nbt.isEmpty()) {
                throw document.error(path, "must contain at least one track");
            }
            tracks.put(nodeId, new NodeTracks(position, rotation, scale, visible, nbt));
        }
        return Map.copyOf(tracks);
    }

    private List<NbtKeyframe> parseNbtTrack(
        JsonArray array,
        String path,
        int durationTicks,
        EmoteJsonDocument document
    ) throws EmoteAnimationLoadException {
        if (array == null) return List.of();
        if (array.isEmpty()) throw document.error(path, "must not be empty");

        List<NbtKeyframe> keyframes = new ArrayList<>();
        Set<String> initialFields = null;
        int previousTick = -1;
        for (int index = 0; index < array.size(); index++) {
            String keyframePath = path + "[" + index + "]";
            JsonObject object = document.requireObject(array.get(index), keyframePath);
            int tick = parseTrackTime(object, keyframePath, durationTicks, previousTick, index, document);
            NbtValue parsed = parseNbtValue(document.requireElement(object, "value", keyframePath), keyframePath + ".value", document);
            List<CompoundTag> options = parsed.options();
            if (index == 0) {
                initialFields = Set.copyOf(options.getFirst().keySet());
                for (int optionIndex = 1; optionIndex < options.size(); optionIndex++) {
                    if (!initialFields.equals(options.get(optionIndex).keySet())) {
                        throw document.error(
                            keyframePath + ".value.options[" + optionIndex + "]",
                            "0t NBT options must declare the same fields"
                        );
                    }
                }
            } else {
                for (int optionIndex = 0; optionIndex < options.size(); optionIndex++) {
                    if (!initialFields.containsAll(options.get(optionIndex).keySet())) {
                        throw document.error(
                            nbtOptionPath(keyframePath + ".value", parsed, optionIndex),
                            "must only modify fields declared by the 0t keyframe"
                        );
                    }
                }
            }
            if (object.size() != 2) {
                throw document.error(keyframePath, "nbt keyframes only support time and value");
            }
            keyframes.add(new NbtKeyframe(tick, parsed));
            previousTick = tick;
        }
        return List.copyOf(keyframes);
    }

    private NbtValue parseNbtValue(JsonElement element, String path, EmoteJsonDocument document)
        throws EmoteAnimationLoadException {
        if (!document.isNotString(element)) {
            return new FixedNbtValue(parseNbt(element.getAsString(), path, document));
        }
        JsonObject object = document.requireObject(element, path);
        String selector = document.requireString(object, "select", path);
        if (selector.isBlank()) {
            throw document.error(path + ".select", "must not be blank");
        }
        AnimationJsonParser.compileMolang(selector, path + ".select", document);
        JsonArray optionArray = document.requireArray(object, "options", path);
        if (optionArray.size() < 2) {
            throw document.error(path + ".options", "must contain at least two options");
        }
        if (object.size() != 2) {
            throw document.error(path, "selected NBT only supports select and options");
        }
        List<CompoundTag> options = new ArrayList<>(optionArray.size());
        for (int index = 0; index < optionArray.size(); index++) {
            String optionPath = path + ".options[" + index + "]";
            if (document.isNotString(optionArray.get(index))) {
                throw document.error(optionPath, "must be a compound SNBT string");
            }
            options.add(parseNbt(optionArray.get(index).getAsString(), optionPath, document));
        }
        return new SelectedNbtValue(new MolangValue(selector, path + ".select"), options);
    }

    private CompoundTag parseNbt(String source, String path, EmoteJsonDocument document) throws EmoteAnimationLoadException {
        CompoundTag parsed;
        try {
            parsed = TagParser.parseCompoundFully(source);
        } catch (Exception exception) {
            throw document.error(path, "invalid compound SNBT", exception);
        }
        for (String field : RUNTIME_OWNED_NBT_FIELDS) {
            if (parsed.contains(field)) {
                throw document.error(path, "must not modify runtime-owned field " + field);
            }
        }
        return parsed;
    }

    private String nbtOptionPath(String path, NbtValue value, int optionIndex) {
        return value instanceof FixedNbtValue ? path : path + ".options[" + optionIndex + "]";
    }

    private List<VectorKeyframe> parseVectorTrack(
        JsonArray array,
        String path,
        int durationTicks,
        EmoteJsonDocument document
    ) throws EmoteAnimationLoadException {
        if (array == null) {
            return List.of();
        }
        if (array.isEmpty()) {
            throw document.error(path, "must not be empty");
        }
        List<VectorKeyframe> keyframes = new ArrayList<>();
        int previousTick = -1;
        for (int index = 0; index < array.size(); index++) {
            String keyframePath = path + "[" + index + "]";
            JsonObject object = document.requireObject(array.get(index), keyframePath);
            int tick = parseTrackTime(object, keyframePath, durationTicks, previousTick, index, document);
            boolean hasValue = object.has("value") && !object.get("value").isJsonNull();
            boolean hasPre = object.has("pre") && !object.get("pre").isJsonNull();
            boolean hasPost = object.has("post") && !object.get("post").isJsonNull();
            VectorValue pre;
            VectorValue post;
            if (hasValue && !hasPre && !hasPost) {
                pre = parseVectorValue(document.requireArray(object, "value", keyframePath), keyframePath + ".value", document);
                post = pre;
            } else if (!hasValue && hasPre && hasPost) {
                pre = parseVectorValue(document.requireArray(object, "pre", keyframePath), keyframePath + ".pre", document);
                post = parseVectorValue(document.requireArray(object, "post", keyframePath), keyframePath + ".post", document);
            } else {
                throw document.error(keyframePath, "must define either value or both pre and post");
            }
            boolean last = index == array.size() - 1;
            if (last && (object.has("interpolation") || object.has("easing"))) {
                throw document.error(keyframePath, "last keyframe must not define interpolation or easing");
            }
            Interpolation interpolation = last ? Interpolation.LINEAR : parseInterpolation(object, keyframePath, document);
            Easing easing = last ? Easing.LINEAR : parseEasing(object, keyframePath, interpolation, document);
            keyframes.add(new VectorKeyframe(tick, pre, post, interpolation, easing));
            previousTick = tick;
        }
        return List.copyOf(keyframes);
    }

    private List<VisibilityKeyframe> parseVisibilityTrack(
        JsonArray array,
        String path,
        int durationTicks,
        EmoteJsonDocument document
    ) throws EmoteAnimationLoadException {
        if (array == null) {
            return List.of();
        }
        if (array.isEmpty()) {
            throw document.error(path, "must not be empty");
        }
        List<VisibilityKeyframe> keyframes = new ArrayList<>();
        int previousTick = -1;
        for (int index = 0; index < array.size(); index++) {
            String keyframePath = path + "[" + index + "]";
            JsonObject object = document.requireObject(array.get(index), keyframePath);
            int tick = parseTrackTime(object, keyframePath, durationTicks, previousTick, index, document);
            JsonElement value = document.requireElement(object, "value", keyframePath);
            VisibilityValue parsed;
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
                parsed = new ConstantVisibility(value.getAsBoolean());
            } else if (!document.isNotString(value) && !value.getAsString().isBlank()) {
                String source = value.getAsString();
                AnimationJsonParser.compileMolang(source, keyframePath + ".value", document);
                parsed = new MolangVisibility(source, keyframePath + ".value");
            } else {
                throw document.error(keyframePath + ".value", "must be a boolean or Molang string");
            }
            if (object.has("interpolation") || object.has("easing") || object.has("pre") || object.has("post")) {
                throw document.error(keyframePath, "visible keyframes only support time and value");
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
        EmoteJsonDocument document
    ) throws EmoteAnimationLoadException {
        int tick = document.requireTime(object, "time", path, 0);
        if (tick < 0 || tick > durationTicks) {
            throw document.error(path + ".time", "must be between 0t and timeline duration");
        }
        if (index == 0 && tick != 0) {
            throw document.error(path + ".time", "first keyframe must be at 0t");
        }
        if (tick <= previousTick) {
            throw document.error(path + ".time", "keyframes must be strictly ordered by time");
        }
        return tick;
    }

    private VectorValue parseVectorValue(JsonArray array, String path, EmoteJsonDocument document)
        throws EmoteAnimationLoadException {
        if (array.size() != 3) {
            throw document.error(path, "must contain 3 values");
        }
        return new VectorValue(
            parseScalar(array.get(0), path + "[0]", document),
            parseScalar(array.get(1), path + "[1]", document),
            parseScalar(array.get(2), path + "[2]", document)
        );
    }

    private ScalarValue parseScalar(JsonElement element, String path, EmoteJsonDocument document)
        throws EmoteAnimationLoadException {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            return new ConstantValue(document.requireFiniteDouble(element, path));
        }
        if (!document.isNotString(element) && !element.getAsString().isBlank()) {
            String source = element.getAsString();
            AnimationJsonParser.compileMolang(source, path, document);
            return new MolangValue(source, path);
        }
        throw document.error(path, "must be a finite number or Molang string");
    }

    private Interpolation parseInterpolation(JsonObject object, String path, EmoteJsonDocument document)
        throws EmoteAnimationLoadException {
        if (!object.has("interpolation")) {
            return Interpolation.LINEAR;
        }
        return switch (document.requireString(object, "interpolation", path)) {
            case "step" -> Interpolation.STEP;
            case "linear" -> Interpolation.LINEAR;
            default -> throw document.error(path + ".interpolation", "must be step or linear");
        };
    }

    private Easing parseEasing(JsonObject object, String path, Interpolation interpolation, EmoteJsonDocument document)
        throws EmoteAnimationLoadException {
        if (!object.has("easing")) {
            return Easing.LINEAR;
        }
        if (interpolation == Interpolation.STEP) {
            throw document.error(path + ".easing", "is not supported by step interpolation");
        }
        String value = document.requireString(object, "easing", path);
        try {
            return Easing.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw document.error(path + ".easing", "unsupported easing: " + value);
        }
    }

    static Node requireNode(
        Map<String, Node> nodes,
        String nodeId,
        String path,
        EmoteJsonDocument document
    )
        throws EmoteAnimationLoadException {
        Node node = nodes.get(nodeId);
        if (node == null) {
            throw document.error(path, "references unknown node: " + nodeId);
        }
        return node;
    }

    private int optionalInterpolationDuration(
        JsonObject object,
        String path,
        int defaultValue,
        EmoteJsonDocument document
    ) throws EmoteAnimationLoadException {
        String key = "interpolation_duration";
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return defaultValue;
        }
        return document.requireTime(object, key, path, 0);
    }
}
