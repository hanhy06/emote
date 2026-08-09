package io.github.hanhy06.emote.animation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
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

public final class EmoteAnimationJsonLoader {
    private static final int SCHEMA_VERSION = 1;
    private static final int TICK_RATE = 20;
    static final int MAX_JSON_BYTES = 8 * 1_024 * 1_024;
    private static final String TRANSFORM_SPACE_PATH = "$.transform_space";
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
    private final EmoteTimelineJsonParser timelineParser = new EmoteTimelineJsonParser();

    public Loaded load(Path sourcePath, String expectedMinecraftVersion) throws EmoteAnimationLoadException {
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
        } catch (EmoteAnimationLoadException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new EmoteAnimationLoadException(sourcePath, "$", "failed to read file", exception);
        }
        return parse(sourcePath, bytes, expectedMinecraftVersion);
    }

    public Loaded parse(Path sourcePath, byte[] bytes, String expectedMinecraftVersion)
        throws EmoteAnimationLoadException {
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(expectedMinecraftVersion, "expectedMinecraftVersion");
        if (bytes.length > MAX_JSON_BYTES) {
            throw new EmoteAnimationLoadException(
                sourcePath,
                "$",
                "file must not exceed " + MAX_JSON_BYTES + " bytes"
            );
        }

        EmoteAnimationJsonReader reader = new EmoteAnimationJsonReader(sourcePath);
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
        reader.requireExactInt(root, "schema_version", "$", SCHEMA_VERSION);
        String minecraftVersion = reader.requireString(root, "minecraft_version", "$");
        if (!minecraftVersion.equals("*") && !minecraftVersion.equals(expectedMinecraftVersion)) {
            throw reader.error("$.minecraft_version", "must equal server version " + expectedMinecraftVersion);
        }
        reader.requireExactInt(root, "tick_rate", "$", TICK_RATE);

        String idText = reader.requireString(root, "id", "$");
        Identifier id = parseId(idText, reader);
        Metadata metadata = parseMetadata(reader.requireObject(root, "metadata", "$"), reader);
        PlayerBehavior player = parsePlayer(reader.requireObject(root, "player", "$"), reader);
        parseTransformSpace(reader.requireObject(root, "transform_space", "$"), reader);
        Map<String, Node> nodes = parseNodes(reader.requireObject(root, "nodes", "$"), reader);
        Timeline timeline = this.timelineParser.parse(reader.requireObject(root, "timeline", "$"), nodes, reader);
        return new Loaded(sourcePath, sha256(bytes), new EmoteAnimation(id, metadata, player, nodes, timeline));
    }

    private Metadata parseMetadata(JsonObject object, EmoteAnimationJsonReader reader)
        throws EmoteAnimationLoadException {
        String name = reader.requireString(object, "name", "$.metadata");
        if (name.isBlank()) {
            throw reader.error("$.metadata.name", "must not be blank");
        }
        String description = reader.requireString(object, "description", "$.metadata");
        return new Metadata(name, description);
    }

    private PlayerBehavior parsePlayer(JsonObject object, EmoteAnimationJsonReader reader)
        throws EmoteAnimationLoadException {
        boolean hidden = reader.requireBoolean(object, "hidden", "$.player");
        JsonObject stopObject = reader.requireObject(object, "stop_conditions", "$.player");
        double movementDistance = reader.requireFiniteDouble(
            reader.requireElement(stopObject, "movement_distance", "$.player.stop_conditions"),
            "$.player.stop_conditions.movement_distance"
        );
        if (movementDistance < 0.0D) {
            throw reader.error("$.player.stop_conditions.movement_distance", "must not be negative");
        }
        return new PlayerBehavior(hidden, new StopConditions(
            movementDistance,
            reader.requireBoolean(stopObject, "jump", "$.player.stop_conditions"),
            reader.requireBoolean(stopObject, "submerge", "$.player.stop_conditions"),
            reader.requireBoolean(stopObject, "ride", "$.player.stop_conditions"),
            reader.requireBoolean(stopObject, "damage", "$.player.stop_conditions"),
            reader.requireBoolean(stopObject, "attack", "$.player.stop_conditions"),
            reader.requireBoolean(stopObject, "game_mode_change", "$.player.stop_conditions")
        ));
    }

    private void parseTransformSpace(JsonObject object, EmoteAnimationJsonReader reader)
        throws EmoteAnimationLoadException {
        requireTransformSpaceString(object, "coordinate_space", "root_local", reader);
        requireTransformSpaceString(object, "matrix_layout", "row_major", reader);
        reader.requireExactInt(object, "matrix_size", TRANSFORM_SPACE_PATH, 16);
    }

    private Map<String, Node> parseNodes(JsonObject object, EmoteAnimationJsonReader reader)
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

    private Node parseNode(JsonObject object, String path, EmoteAnimationJsonReader reader)
        throws EmoteAnimationLoadException {
        String type = reader.requireString(object, "type", path);
        Matrix defaultMatrix = reader.requireMatrix(object, "default_matrix", path);
        if (type.equals("anchor")) {
            if (object.has("visible")) {
                throw reader.error(path + ".visible", "is not supported by anchor nodes");
            }
            if (object.has("entity_nbt")) {
                throw reader.error(path + ".entity_nbt", "is not supported by anchor nodes");
            }
            return new AnchorNode(defaultMatrix);
        }

        defaultMatrix = EmoteMatrixNormalizer.stabilize(defaultMatrix);
        boolean visible = optionalVisible(object, path, reader);
        CompoundTag entityNbt = optionalEntityNbt(object, path, reader);
        return switch (type) {
            case "item_display" -> new ItemNode(
                visible,
                defaultMatrix,
                entityNbt,
                requireCompoundSnbt(object, "item_stack_snbt", path, reader),
                parseItemDisplay(object, path, reader),
                parseSkin(object, path, reader)
            );
            case "block_display" -> new BlockNode(
                visible,
                defaultMatrix,
                entityNbt,
                requireCompoundSnbt(object, "block_state_snbt", path, reader)
            );
            case "text_display" -> new TextNode(
                visible,
                defaultMatrix,
                entityNbt,
                reader.requireElement(object, "text", path)
            );
            default -> throw reader.error(path + ".type", "unsupported node type: " + type);
        };
    }

    private String parseItemDisplay(JsonObject object, String path, EmoteAnimationJsonReader reader)
        throws EmoteAnimationLoadException {
        String value = reader.requireString(object, "item_display", path);
        if (!ITEM_DISPLAY_VALUES.contains(value)) {
            throw reader.error(path + ".item_display", "unsupported item display context: " + value);
        }
        return value;
    }

    private Skin parseSkin(JsonObject object, String path, EmoteAnimationJsonReader reader)
        throws EmoteAnimationLoadException {
        JsonElement element = object.get("skin");
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonObject()) {
            throw reader.error(path + ".skin", "must be an object");
        }
        JsonObject skin = element.getAsJsonObject();
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
        return new Skin(part, order);
    }

    private Identifier parseId(String value, EmoteAnimationJsonReader reader) throws EmoteAnimationLoadException {
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

    private CompoundTag optionalEntityNbt(JsonObject object, String path, EmoteAnimationJsonReader reader)
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
        EmoteAnimationJsonReader reader
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

    private void requireTransformSpaceString(
        JsonObject object,
        String key,
        String expected,
        EmoteAnimationJsonReader reader
    ) throws EmoteAnimationLoadException {
        String value = reader.requireString(object, key, TRANSFORM_SPACE_PATH);
        if (!value.equals(expected)) {
            throw reader.error(TRANSFORM_SPACE_PATH + "." + key, "must equal " + expected);
        }
    }

    private boolean optionalVisible(JsonObject object, String path, EmoteAnimationJsonReader reader)
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
