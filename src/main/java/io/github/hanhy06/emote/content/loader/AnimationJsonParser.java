package io.github.hanhy06.emote.content.loader;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.hanhy06.emote.playback.molang.MolangEngine;
import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.ParticipantRole;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;
import io.github.hanhy06.emote.content.LoadedAnimation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import static io.github.hanhy06.emote.api.animation.EmoteAnimation.*;

public final class AnimationJsonParser {
    private static final int SCHEMA_VERSION = 4;
    private static final Set<String> FORBIDDEN_ENTITY_NBT = Set.of(
        "id", "UUID", "Pos", "Motion", "Rotation", "Passengers", "Tags",
        "transformation", "interpolation_duration", "start_interpolation", "teleport_duration",
        "item", "item_display", "block_state", "text"
    );
    private static final Set<String> ITEM_DISPLAY_VALUES = Set.of(
        "none",
        "thirdperson_lefthand",
        "thirdperson_righthand",
        "firstperson_lefthand",
        "firstperson_righthand",
        "head",
        "gui",
        "ground",
        "fixed",
        "on_shelf"
    );
    private final TimelineJsonParser timelineParser = new TimelineJsonParser();

    public LoadedAnimation parse(Path sourcePath) throws EmoteAnimationLoadException {
        return parse(EmoteJsonDocument.read(sourcePath));
    }

    public LoadedAnimation parse(Path sourcePath, byte[] bytes)
        throws EmoteAnimationLoadException {
        return parse(EmoteJsonDocument.parse(sourcePath, bytes));
    }

    LoadedAnimation parse(EmoteJsonDocument document) throws EmoteAnimationLoadException {
        EmoteJsonReader reader = document.reader();
        JsonObject root = document.root();
        if (!document.type().equals("animation")) {
            throw reader.error("$.type", "must equal animation");
        }
        reader.requireExactInt(root, "schema_version", "$", SCHEMA_VERSION);

        String idText = reader.requireString(root, "id", "$");
        Identifier id = parseId(idText, reader);
        EmoteMetadata metadata = parseMetadata(reader.requireObject(root, "metadata", "$"), reader);
        JsonObject settingsObject = reader.requireObject(root, "settings", "$");
        Settings settings = parseSettings(settingsObject, reader);
        MolangPrograms molang = parseMolang(reader.optionalObject(root, "molang", "$"), settings, reader);
        Map<String, Node> nodes = parseNodes(reader.requireObject(root, "nodes", "$"), reader);
        Timeline timeline = this.timelineParser.parse(reader.requireObject(root, "timeline", "$"), nodes, reader);
        return new LoadedAnimation(
            document.sourcePath(),
            sha256(document.bytes()),
            new EmoteAnimation(id, metadata, settings, molang, nodes, timeline)
        );
    }

    private MolangPrograms parseMolang(JsonObject object, Settings settings, EmoteJsonReader reader)
        throws EmoteAnimationLoadException {
        if (object == null) {
            return MolangPrograms.empty();
        }
        String initialize = optionalProgram(object, "initialize", "$.molang", reader);
        String tick = optionalProgram(object, "tick", "$.molang", reader);
        if (settings.playback().mode() == LoopMode.SERVER_SYNC && tick != null) {
            throw reader.error("$.molang.tick", "is not supported by server_sync playback");
        }
        return new MolangPrograms(initialize, tick);
    }

    private String optionalProgram(JsonObject object, String key, String path, EmoteJsonReader reader)
        throws EmoteAnimationLoadException {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (reader.isNotString(element)) {
            throw reader.error(path + "." + key, "must be a string");
        }
        String source = element.getAsString();
        if (source.isBlank()) {
            throw reader.error(path + "." + key, "must not be blank");
        }
        compileMolang(source, path + "." + key, reader);
        return source;
    }

    static EmoteMetadata parseMetadata(JsonObject object, EmoteJsonReader reader)
        throws EmoteAnimationLoadException {
        String name = reader.requireString(object, "name", "$.metadata");
        if (name.isBlank()) {
            throw reader.error("$.metadata.name", "must not be blank");
        }
        String description = reader.requireString(object, "description", "$.metadata");
        LinkedHashMap<String, JsonElement> additional = new LinkedHashMap<>();
        object.entrySet().stream()
            .filter(entry -> !entry.getKey().equals("name") && !entry.getKey().equals("description"))
            .forEach(entry -> additional.put(entry.getKey(), entry.getValue()));
        return new EmoteMetadata(name, description, additional);
    }

    private Settings parseSettings(JsonObject object, EmoteJsonReader reader)
        throws EmoteAnimationLoadException {
        boolean standalone = reader.requireBoolean(object, "standalone", "$.settings");
        int cooldownTicks = reader.requireTime(object, "cooldown", "$.settings", 0);
        EmotePlayerBehavior player = parsePlayer(
            reader.requireObject(object, "player", "$.settings"),
            "$.settings.player",
            reader
        );
        JsonObject playbackObject = reader.requireObject(object, "playback", "$.settings");
        String modeText = reader.requireString(playbackObject, "mode", "$.settings.playback");
        LoopMode mode = switch (modeText) {
            case "once" -> LoopMode.ONCE;
            case "hold" -> LoopMode.HOLD;
            case "loop" -> LoopMode.LOOP;
            case "server_sync" -> LoopMode.SERVER_SYNC;
            default -> throw reader.error("$.settings.playback.mode", "unsupported playback mode: " + modeText);
        };
        int loopDelayTicks = reader.requireTime(playbackObject, "loop_delay", "$.settings.playback", 0);
        try {
            return new Settings(standalone, cooldownTicks, player, new PlaybackSettings(mode, loopDelayTicks));
        } catch (IllegalArgumentException exception) {
            throw reader.error("$.settings.playback.loop_delay", exception.getMessage(), exception);
        }
    }

    static EmotePlayerBehavior parsePlayer(JsonObject object, String path, EmoteJsonReader reader)
        throws EmoteAnimationLoadException {
        boolean hidden = reader.requireBoolean(object, "hidden", path);
        JsonObject stopObject = reader.requireObject(object, "stop_conditions", path);
        String stopPath = path + ".stop_conditions";
        double movementDistance = reader.requireFiniteDouble(
            reader.requireElement(stopObject, "movement_distance", stopPath),
            stopPath + ".movement_distance"
        );
        if (movementDistance < 0.0D) {
            throw reader.error(stopPath + ".movement_distance", "must not be negative");
        }
        return new EmotePlayerBehavior(hidden, new EmotePlayerBehavior.StopConditions(
            movementDistance,
            reader.requireBoolean(stopObject, "jump", stopPath),
            reader.requireBoolean(stopObject, "submerge", stopPath),
            reader.requireBoolean(stopObject, "ride", stopPath),
            reader.requireBoolean(stopObject, "damage", stopPath),
            reader.requireBoolean(stopObject, "attack", stopPath),
            reader.requireBoolean(stopObject, "game_mode_change", stopPath)
        ));
    }

    private Map<String, Node> parseNodes(JsonObject object, EmoteJsonReader reader)
        throws EmoteAnimationLoadException {
        if (object.isEmpty()) {
            throw reader.error("$.nodes", "must not be empty");
        }
        LinkedHashMap<String, Node> nodes = new LinkedHashMap<>();
        LinkedHashMap<String, JsonObject> definitions = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String nodeId = entry.getKey();
            String path = "$.nodes." + nodeId;
            if (nodeId.isBlank()) {
                throw reader.error("$.nodes", "node id must not be blank");
            }
            if (!entry.getValue().isJsonObject()) {
                throw reader.error(path, "must be an object");
            }
            definitions.put(nodeId, entry.getValue().getAsJsonObject());
        }
        Set<String> visiting = new HashSet<>();
        for (String nodeId : definitions.keySet()) {
            resolveNode(nodeId, definitions, nodes, visiting, reader);
        }
        return Map.copyOf(nodes);
    }

    private Node resolveNode(
        String nodeId,
        Map<String, JsonObject> definitions,
        Map<String, Node> nodes,
        Set<String> visiting,
        EmoteJsonReader reader
    ) throws EmoteAnimationLoadException {
        Node existing = nodes.get(nodeId);
        if (existing != null) {
            return existing;
        }
        String path = "$.nodes." + nodeId;
        if (!visiting.add(nodeId)) {
            throw reader.error(path + ".parent", "creates a parent cycle");
        }
        JsonObject object = definitions.get(nodeId);
        String parentId = optionalParent(object, path, reader);
        NodeSpace space;
        if (parentId == null) {
            space = requireNodeSpace(object, path, reader);
        } else {
            if (object.has("space")) {
                throw reader.error(path + ".space", "is not allowed on child nodes");
            }
            if (!definitions.containsKey(parentId)) {
                throw reader.error(path + ".parent", "references unknown node: " + parentId);
            }
            space = resolveNode(parentId, definitions, nodes, visiting, reader).space();
        }
        Node node = parseNode(object, path, parentId, space, reader);
        visiting.remove(nodeId);
        nodes.put(nodeId, node);
        return node;
    }

    private String optionalParent(JsonObject object, String path, EmoteJsonReader reader)
        throws EmoteAnimationLoadException {
        JsonElement element = object.get("parent");
        if (element == null || element.isJsonNull()) {
            return null;
        }
        String parent = reader.requireString(object, "parent", path);
        if (parent.isBlank()) {
            throw reader.error(path + ".parent", "must not be blank");
        }
        return parent;
    }

    private Node parseNode(JsonObject object, String path, String parentId, NodeSpace space, EmoteJsonReader reader)
        throws EmoteAnimationLoadException {
        String type = reader.requireString(object, "type", path);
        LocalTransform transform = parseTransform(reader.requireObject(object, "transform", path), path + ".transform", reader);
        if (type.equals("anchor")) {
            if (object.has("visible")) {
                throw reader.error(path + ".visible", "is not supported by anchor nodes");
            }
            if (object.has("entity_nbt")) {
                throw reader.error(path + ".entity_nbt", "is not supported by anchor nodes");
            }
            return new AnchorNode(space, parentId, transform);
        }

        boolean visible = optionalVisible(object, path, reader);
        CompoundTag entityNbt = optionalEntityNbt(object, path, reader);
        return switch (type) {
            case "item_display" -> new ItemNode(
                visible,
                space,
                parentId,
                transform,
                entityNbt,
                parseItemSource(object, space, path, reader),
                parseItemDisplay(object, path, reader),
                parseSkin(object, space, path, reader)
            );
            case "block_display" -> new BlockNode(
                visible,
                space,
                parentId,
                transform,
                entityNbt,
                requireCompoundSnbt(object, "block_state_snbt", path, reader)
            );
            case "text_display" -> new TextNode(
                visible,
                space,
                parentId,
                transform,
                entityNbt,
                reader.requireElement(object, "text", path)
            );
            default -> throw reader.error(path + ".type", "unsupported node type: " + type);
        };
    }

    private LocalTransform parseTransform(JsonObject object, String path, EmoteJsonReader reader)
        throws EmoteAnimationLoadException {
        return new LocalTransform(
            requireVec3(object, "position", path, reader),
            requireVec3(object, "rotation", path, reader),
            requireVec3(object, "scale", path, reader)
        );
    }

    private Vec3 requireVec3(JsonObject object, String key, String path, EmoteJsonReader reader)
        throws EmoteAnimationLoadException {
        var array = reader.requireArray(object, key, path);
        String fieldPath = path + "." + key;
        if (array.size() != 3) {
            throw reader.error(fieldPath, "must contain 3 values");
        }
        return new Vec3(
            reader.requireFiniteDouble(array.get(0), fieldPath + "[0]"),
            reader.requireFiniteDouble(array.get(1), fieldPath + "[1]"),
            reader.requireFiniteDouble(array.get(2), fieldPath + "[2]")
        );
    }

    private NodeSpace requireNodeSpace(JsonObject object, String path, EmoteJsonReader reader)
        throws EmoteAnimationLoadException {
        String value = reader.requireString(object, "space", path);
        return switch (value) {
            case "scene" -> NodeSpace.SCENE;
            case "initiator" -> NodeSpace.INITIATOR;
            case "partner" -> NodeSpace.PARTNER;
            default -> throw reader.error(path + ".space", "unsupported node space: " + value);
        };
    }

    private String parseItemDisplay(JsonObject object, String path, EmoteJsonReader reader)
        throws EmoteAnimationLoadException {
        String value = reader.requireString(object, "item_display", path);
        if (!ITEM_DISPLAY_VALUES.contains(value)) {
            throw reader.error(path + ".item_display", "unsupported item display context: " + value);
        }
        return value;
    }

    private ItemSource parseItemSource(JsonObject object, NodeSpace space, String path, EmoteJsonReader reader)
        throws EmoteAnimationLoadException {
        boolean hasStack = object.has("item_stack_snbt");
        boolean hasSource = object.has("item_source");
        if (hasStack == hasSource) {
            throw reader.error(path, "must define exactly one of item_stack_snbt or item_source");
        }
        if (hasStack) {
            return new FixedItemSource(requireCompoundSnbt(object, "item_stack_snbt", path, reader));
        }

        String sourcePath = path + ".item_source";
        JsonObject source = reader.requireObject(object, "item_source", path);
        String type = reader.requireString(source, "type", sourcePath);
        if (!type.equals("participant_hand")) {
            throw reader.error(sourcePath + ".type", "unsupported item source: " + type);
        }
        if (space == NodeSpace.SCENE) {
            throw reader.error(sourcePath, "participant hand items require initiator or partner node space");
        }
        String arm = reader.requireString(source, "arm", sourcePath);
        return new ParticipantHandItemSource(switch (arm) {
            case "right" -> HumanoidArm.RIGHT;
            case "left" -> HumanoidArm.LEFT;
            default -> throw reader.error(sourcePath + ".arm", "unsupported physical arm: " + arm);
        });
    }

    private Skin parseSkin(JsonObject object, NodeSpace nodeSpace, String path, EmoteJsonReader reader)
        throws EmoteAnimationLoadException {
        JsonElement element = object.get("skin");
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonObject()) {
            throw reader.error(path + ".skin", "must be an object");
        }
        JsonObject skin = element.getAsJsonObject();
        JsonElement participantElement = skin.get("participant");
        String participantText = participantElement == null || participantElement.isJsonNull()
            ? "initiator"
            : reader.requireString(skin, "participant", path + ".skin");
        ParticipantRole participant = switch (participantText) {
            case "initiator" -> ParticipantRole.INITIATOR;
            case "partner" -> ParticipantRole.PARTNER;
            default -> throw reader.error(path + ".skin.participant", "unsupported participant: " + participantText);
        };
        if (nodeSpace != NodeSpace.forParticipant(participant)) {
            throw reader.error(path + ".skin.participant", "must match the node space");
        }
        String partText = reader.requireString(skin, "part", path + ".skin");
        SkinPart part = switch (partText) {
            case "head" -> SkinPart.HEAD;
            case "body" -> SkinPart.BODY;
            case "left_arm" -> SkinPart.LEFT_ARM;
            case "right_arm" -> SkinPart.RIGHT_ARM;
            case "left_leg" -> SkinPart.LEFT_LEG;
            case "right_leg" -> SkinPart.RIGHT_LEG;
            default -> throw reader.error(path + ".skin.part", "unsupported skin part: " + partText);
        };
        int order = reader.requireInt(skin, "order", path + ".skin");
        if (order < 0) {
            throw reader.error(path + ".skin.order", "must not be negative");
        }
        return new Skin(participant, part, order);
    }

    private Identifier parseId(String value, EmoteJsonReader reader) throws EmoteAnimationLoadException {
        int separator = value.indexOf(':');
        if (separator <= 0 || separator == value.length() - 1) {
            throw reader.error("$.id", "must use namespace:path format");
        }
        Identifier id = Identifier.tryParse(value);
        if (id == null || !id.toString().equals(value)) {
            throw reader.error("$.id", "must be a valid lowercase Minecraft identifier");
        }
        return id;
    }

    private CompoundTag optionalEntityNbt(JsonObject object, String path, EmoteJsonReader reader)
        throws EmoteAnimationLoadException {
        String key = "entity_nbt";
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return new CompoundTag();
        }
        CompoundTag tag = requireCompoundSnbt(object, key, path, reader);
        for (String forbidden : FORBIDDEN_ENTITY_NBT) {
            if (tag.contains(forbidden)) {
                throw reader.error(path + ".entity_nbt", "must not define runtime-owned field: " + forbidden);
            }
        }
        return tag;
    }

    static void compileMolang(String source, String path, EmoteJsonReader reader) throws EmoteAnimationLoadException {
        try {
            MolangEngine.INSTANCE.compile(source);
        } catch (MolangEngine.MolangCompileException exception) {
            throw reader.error(path, "invalid Molang program", exception);
        }
    }

    private CompoundTag requireCompoundSnbt(
        JsonObject object,
        String key,
        String path,
        EmoteJsonReader reader
    )
        throws EmoteAnimationLoadException {
        String fieldPath = path + "." + key;
        String value = reader.requireString(object, key, path);
        try {
            return TagParser.parseCompoundFully(value);
        } catch (CommandSyntaxException exception) {
            throw reader.error(fieldPath, "invalid compound SNBT", exception);
        }
    }

    private boolean optionalVisible(JsonObject object, String path, EmoteJsonReader reader)
        throws EmoteAnimationLoadException {
        if (!object.has("visible") || object.get("visible").isJsonNull()) {
            return true;
        }
        return reader.requireBoolean(object, "visible", path);
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
