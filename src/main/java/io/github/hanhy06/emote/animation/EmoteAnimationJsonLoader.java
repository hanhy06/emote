package io.github.hanhy06.emote.animation;

import com.google.gson.*;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
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

import static io.github.hanhy06.emote.animation.EmoteAnimation.*;

public final class EmoteAnimationJsonLoader {
    private static final int SCHEMA_VERSION = 1;
    private static final int TICK_RATE = 20;
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
            bytes = Files.readAllBytes(sourcePath);
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

        JsonElement rootElement;
        try {
            rootElement = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        } catch (JsonParseException exception) {
            throw new EmoteAnimationLoadException(sourcePath, "$", "invalid JSON", exception);
        }
        if (!rootElement.isJsonObject()) {
            throw error(sourcePath, "$", "must be an object");
        }

        JsonObject root = rootElement.getAsJsonObject();
        requireExactInt(root, "schema_version", "$", SCHEMA_VERSION, sourcePath);
        String minecraftVersion = requireString(root, "minecraft_version", "$", sourcePath);
        if (!minecraftVersion.equals(expectedMinecraftVersion)) {
            throw error(sourcePath, "$.minecraft_version", "must equal server version " + expectedMinecraftVersion);
        }
        requireExactInt(root, "tick_rate", "$", TICK_RATE, sourcePath);

        String idText = requireString(root, "id", "$", sourcePath);
        Identifier id = parseId(idText, sourcePath);
        Metadata metadata = parseMetadata(requireObject(root, "metadata", "$", sourcePath), sourcePath);
        parseTransformSpace(requireObject(root, "transform_space", "$", sourcePath), sourcePath);
        Map<String, Node> nodes = parseNodes(requireObject(root, "nodes", "$", sourcePath), sourcePath);
        Timeline timeline = this.timelineParser.parse(requireObject(root, "timeline", "$", sourcePath), nodes, sourcePath);
        return new Loaded(sourcePath, sha256(bytes), new EmoteAnimation(id, metadata, nodes, timeline));
    }

    private Metadata parseMetadata(JsonObject object, Path sourcePath) throws EmoteAnimationLoadException {
        String name = requireString(object, "name", "$.metadata", sourcePath);
        if (name.isBlank()) {
            throw error(sourcePath, "$.metadata.name", "must not be blank");
        }
        String description = requireString(object, "description", "$.metadata", sourcePath);
        boolean hidePlayer = requireBoolean(object, "hide_player", "$.metadata", sourcePath);
        return new Metadata(name, description, hidePlayer);
    }

    private void parseTransformSpace(JsonObject object, Path sourcePath) throws EmoteAnimationLoadException {
        requireTransformSpaceString(object, "coordinate_space", "root_local", sourcePath);
        requireTransformSpaceString(object, "matrix_layout", "row_major", sourcePath);
        requireExactInt(object, "matrix_size", TRANSFORM_SPACE_PATH, 16, sourcePath);
    }

    private Map<String, Node> parseNodes(JsonObject object, Path sourcePath) throws EmoteAnimationLoadException {
        LinkedHashMap<String, Node> nodes = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String nodeId = entry.getKey();
            String path = "$.nodes." + nodeId;
            if (nodeId.isBlank()) {
                throw error(sourcePath, "$.nodes", "node id must not be blank");
            }
            if (!entry.getValue().isJsonObject()) {
                throw error(sourcePath, path, "must be an object");
            }
            nodes.put(nodeId, parseNode(entry.getValue().getAsJsonObject(), path, sourcePath));
        }
        return Collections.unmodifiableMap(nodes);
    }

    private Node parseNode(JsonObject object, String path, Path sourcePath) throws EmoteAnimationLoadException {
        String type = requireString(object, "type", path, sourcePath);
        Matrix defaultMatrix = parseMatrix(requireArray(object, "default_matrix", path, sourcePath), path + ".default_matrix", sourcePath);
        if (type.equals("anchor")) {
            if (object.has("visible")) {
                throw error(sourcePath, path + ".visible", "is not supported by anchor nodes");
            }
            if (object.has("entity_nbt")) {
                throw error(sourcePath, path + ".entity_nbt", "is not supported by anchor nodes");
            }
            return new AnchorNode(defaultMatrix);
        }

        boolean visible = optionalVisible(object, path, sourcePath);
        CompoundTag entityNbt = optionalEntityNbt(object, path, sourcePath);
        return switch (type) {
            case "item_display" -> new ItemNode(
                visible,
                defaultMatrix,
                entityNbt,
                requireCompoundSnbt(object, "item_stack_snbt", path, sourcePath),
                parseItemDisplay(object, path, sourcePath),
                parseSkin(object, path, sourcePath)
            );
            case "block_display" -> new BlockNode(
                visible,
                defaultMatrix,
                entityNbt,
                requireCompoundSnbt(object, "block_state_snbt", path, sourcePath)
            );
            case "text_display" -> new TextNode(
                visible,
                defaultMatrix,
                entityNbt,
                requireElement(object, "text", path, sourcePath)
            );
            default -> throw error(sourcePath, path + ".type", "unsupported node type: " + type);
        };
    }

    private String parseItemDisplay(JsonObject object, String path, Path sourcePath) throws EmoteAnimationLoadException {
        String value = requireString(object, "item_display", path, sourcePath);
        if (!ITEM_DISPLAY_VALUES.contains(value)) {
            throw error(sourcePath, path + ".item_display", "unsupported item display context: " + value);
        }
        return value;
    }

    private Skin parseSkin(JsonObject object, String path, Path sourcePath) throws EmoteAnimationLoadException {
        JsonElement element = object.get("skin");
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonObject()) {
            throw error(sourcePath, path + ".skin", "must be an object");
        }
        JsonObject skin = element.getAsJsonObject();
        String partText = requireString(skin, "part", path + ".skin", sourcePath);
        SkinPart part = switch (partText) {
            case "head" -> SkinPart.HEAD;
            case "body" -> SkinPart.BODY;
            case "left_arm" -> SkinPart.LEFT_ARM;
            case "right_arm" -> SkinPart.RIGHT_ARM;
            case "left_leg" -> SkinPart.LEFT_LEG;
            case "right_leg" -> SkinPart.RIGHT_LEG;
            default -> throw error(sourcePath, path + ".skin.part", "unsupported skin part: " + partText);
        };
        int order = requireInt(skin, "order", path + ".skin", sourcePath);
        if (order < 0) {
            throw error(sourcePath, path + ".skin.order", "must not be negative");
        }
        return new Skin(part, order);
    }

    static Matrix parseMatrix(JsonArray array, String path, Path sourcePath) throws EmoteAnimationLoadException {
        if (array.size() != 16) {
            throw error(sourcePath, path, "must contain 16 values");
        }
        List<Double> values = new ArrayList<>(16);
        for (int index = 0; index < array.size(); index++) {
            values.add(requireFiniteDouble(array.get(index), path + "[" + index + "]", sourcePath));
        }
        return new Matrix(values);
    }

    private Identifier parseId(String value, Path sourcePath) throws EmoteAnimationLoadException {
        int separator = value.indexOf(':');
        if (separator <= 0 || separator == value.length() - 1) {
            throw error(sourcePath, "$.id", "must use namespace:path format");
        }
        Identifier id = Identifier.tryParse(value);
        if (id == null || !id.toString().equals(value)) {
            throw error(sourcePath, "$.id", "must be a valid lowercase Minecraft identifier");
        }
        return id;
    }

    private CompoundTag optionalEntityNbt(JsonObject object, String path, Path sourcePath)
        throws EmoteAnimationLoadException {
        String key = "entity_nbt";
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return new CompoundTag();
        }
        return requireCompoundSnbt(object, key, path, sourcePath);
    }

    private CompoundTag requireCompoundSnbt(JsonObject object, String key, String path, Path sourcePath)
        throws EmoteAnimationLoadException {
        String fieldPath = path + "." + key;
        String value = requireString(object, key, path, sourcePath);
        try {
            return TagParser.parseCompoundFully(value);
        } catch (CommandSyntaxException exception) {
            throw new EmoteAnimationLoadException(sourcePath, fieldPath, "invalid compound SNBT", exception);
        }
    }

    static JsonObject requireObject(JsonObject object, String key, String path, Path sourcePath)
        throws EmoteAnimationLoadException {
        return requireObject(requireElement(object, key, path, sourcePath), path + "." + key, sourcePath);
    }

    static JsonObject requireObject(JsonElement element, String path, Path sourcePath)
        throws EmoteAnimationLoadException {
        if (!element.isJsonObject()) {
            throw error(sourcePath, path, "must be an object");
        }
        return element.getAsJsonObject();
    }

    static JsonObject optionalObject(JsonObject object, String key, String path, Path sourcePath)
        throws EmoteAnimationLoadException {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return requireObject(element, path + "." + key, sourcePath);
    }

    static JsonArray requireArray(JsonObject object, String key, String path, Path sourcePath)
        throws EmoteAnimationLoadException {
        JsonElement element = requireElement(object, key, path, sourcePath);
        if (!element.isJsonArray()) {
            throw error(sourcePath, path + "." + key, "must be an array");
        }
        return element.getAsJsonArray();
    }

    static JsonArray optionalArray(JsonObject object, String key, String path, Path sourcePath)
        throws EmoteAnimationLoadException {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonArray()) {
            throw error(sourcePath, path + "." + key, "must be an array");
        }
        return element.getAsJsonArray();
    }

    static JsonElement requireElement(JsonObject object, String key, String path, Path sourcePath)
        throws EmoteAnimationLoadException {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            throw error(sourcePath, path + "." + key, "is required");
        }
        return element;
    }

    static String requireString(JsonObject object, String key, String path, Path sourcePath)
        throws EmoteAnimationLoadException {
        JsonElement element = requireElement(object, key, path, sourcePath);
        if (!isString(element)) {
            throw error(sourcePath, path + "." + key, "must be a string");
        }
        return element.getAsString();
    }

    private void requireTransformSpaceString(
        JsonObject object,
        String key,
        String expected,
        Path sourcePath
    ) throws EmoteAnimationLoadException {
        String value = requireString(object, key, TRANSFORM_SPACE_PATH, sourcePath);
        if (!value.equals(expected)) {
            throw error(sourcePath, TRANSFORM_SPACE_PATH + "." + key, "must equal " + expected);
        }
    }

    static boolean requireBoolean(JsonObject object, String key, String path, Path sourcePath)
        throws EmoteAnimationLoadException {
        JsonElement element = requireElement(object, key, path, sourcePath);
        if (!isBoolean(element)) {
            throw error(sourcePath, path + "." + key, "must be a boolean");
        }
        return element.getAsBoolean();
    }

    private boolean optionalVisible(JsonObject object, String path, Path sourcePath)
        throws EmoteAnimationLoadException {
        if (!object.has("visible") || object.get("visible").isJsonNull()) {
            return true;
        }
        return requireBoolean(object, "visible", path, sourcePath);
    }

    static int requireInt(JsonObject object, String key, String path, Path sourcePath)
        throws EmoteAnimationLoadException {
        JsonElement element = requireElement(object, key, path, sourcePath);
        if (!isNumber(element)) {
            throw error(sourcePath, path + "." + key, "must be an integer");
        }
        try {
            return element.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException exception) {
            throw error(sourcePath, path + "." + key, "must be a 32-bit integer");
        }
    }

    private void requireExactInt(JsonObject object, String key, String path, int expected, Path sourcePath)
        throws EmoteAnimationLoadException {
        int value = requireInt(object, key, path, sourcePath);
        if (value != expected) {
            throw error(sourcePath, path + "." + key, "must equal " + expected);
        }
    }

    static double requireFiniteDouble(JsonElement element, String path, Path sourcePath)
        throws EmoteAnimationLoadException {
        if (!isNumber(element)) {
            throw error(sourcePath, path, "must be a number");
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value)) {
            throw error(sourcePath, path, "must be finite");
        }
        return value;
    }

    static boolean isString(JsonElement element) {
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }

    private static boolean isBoolean(JsonElement element) {
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean();
    }

    static boolean isNumber(JsonElement element) {
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber();
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static EmoteAnimationLoadException error(Path sourcePath, String fieldPath, String message) {
        return new EmoteAnimationLoadException(sourcePath, fieldPath, message);
    }
}
