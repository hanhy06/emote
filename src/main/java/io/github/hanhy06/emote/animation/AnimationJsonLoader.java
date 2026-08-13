package io.github.hanhy06.emote.animation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.ParticipantRole;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import static io.github.hanhy06.emote.api.animation.EmoteAnimation.*;

public final class AnimationJsonLoader {
    private static final int SCHEMA_VERSION = 3;
    static final int MAX_JSON_BYTES = 8 * 1_024 * 1_024;
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

    public Loaded load(Path sourcePath) throws EmoteAnimationLoadException {
        byte[] bytes;
        try {
            long fileSize = Files.size(sourcePath);
            if (fileSize > MAX_JSON_BYTES) {
                throw new EmoteAnimationLoadException(
                    sourcePath,
                    "$",
                    "file must not exceed " + MAX_JSON_BYTES + " bytes"
                );
            }
            bytes = Files.readAllBytes(sourcePath);
        } catch (IOException exception) {
            throw new EmoteAnimationLoadException(sourcePath, "$", "failed to read file", exception);
        }
        return parse(sourcePath, bytes);
    }

    public Loaded parse(Path sourcePath, byte[] bytes)
        throws EmoteAnimationLoadException {
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length > MAX_JSON_BYTES) {
            throw new EmoteAnimationLoadException(
                sourcePath,
                "$",
                "file must not exceed " + MAX_JSON_BYTES + " bytes"
            );
        }

        EmoteJsonReader reader = new EmoteJsonReader(sourcePath);
        JsonElement rootElement;
        try {
            rootElement = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        } catch (JsonParseException exception) {
            throw reader.error("$", "invalid JSON", exception);
        }
        if (!rootElement.isJsonObject()) {
            throw reader.error("$", "must be an object");
        }

        JsonObject root = rootElement.getAsJsonObject();
        if (!reader.requireString(root, "type", "$").equals("animation")) {
            throw reader.error("$.type", "must equal animation");
        }
        reader.requireExactInt(root, "schema_version", "$", SCHEMA_VERSION);

        String idText = reader.requireString(root, "id", "$");
        Identifier id = parseId(idText, reader);
        EmoteMetadata metadata = parseMetadata(reader.requireObject(root, "metadata", "$"), reader);
        JsonObject settingsObject = reader.requireObject(root, "settings", "$");
        Settings settings = parseSettings(settingsObject, reader);
        Map<String, Node> nodes = parseNodes(reader.requireObject(root, "nodes", "$"), reader);
        Timeline timeline = this.timelineParser.parse(reader.requireObject(root, "timeline", "$"), nodes, reader);
        return new Loaded(sourcePath, sha256(bytes), new EmoteAnimation(id, metadata, settings, nodes, timeline));
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
        LinkedHashMap<String, Node> nodes = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String nodeId = entry.getKey();
            String path = "$.nodes." + nodeId;
            if (nodeId.isBlank()) {
                throw reader.error("$.nodes", "node id must not be blank");
            }
            if (!entry.getValue().isJsonObject()) {
                throw reader.error(path, "must be an object");
            }
            nodes.put(nodeId, parseNode(entry.getValue().getAsJsonObject(), path, reader));
        }
        return Map.copyOf(nodes);
    }

    private Node parseNode(JsonObject object, String path, EmoteJsonReader reader)
        throws EmoteAnimationLoadException {
        String type = reader.requireString(object, "type", path);
        NodeSpace space = parseNodeSpace(object, path, reader);
        Matrix defaultMatrix = reader.requireMatrix(object, "default_matrix", path);
        if (type.equals("anchor")) {
            if (object.has("visible")) {
                throw reader.error(path + ".visible", "is not supported by anchor nodes");
            }
            if (object.has("entity_nbt")) {
                throw reader.error(path + ".entity_nbt", "is not supported by anchor nodes");
            }
            return new AnchorNode(space, defaultMatrix);
        }

        defaultMatrix = MatrixNormalizer.stabilize(defaultMatrix);
        boolean visible = optionalVisible(object, path, reader);
        CompoundTag entityNbt = optionalEntityNbt(object, path, reader);
        return switch (type) {
            case "item_display" -> new ItemNode(
                visible,
                space,
                defaultMatrix,
                entityNbt,
                requireCompoundSnbt(object, "item_stack_snbt", path, reader),
                parseItemDisplay(object, path, reader),
                parseSkin(object, space, path, reader)
            );
            case "block_display" -> new BlockNode(
                visible,
                space,
                defaultMatrix,
                entityNbt,
                requireCompoundSnbt(object, "block_state_snbt", path, reader)
            );
            case "text_display" -> new TextNode(
                visible,
                space,
                defaultMatrix,
                entityNbt,
                reader.requireElement(object, "text", path)
            );
            default -> throw reader.error(path + ".type", "unsupported node type: " + type);
        };
    }

    private NodeSpace parseNodeSpace(JsonObject object, String path, EmoteJsonReader reader)
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
        String participantText = reader.requireString(skin, "participant", path + ".skin");
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
        return requireCompoundSnbt(object, key, path, reader);
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
