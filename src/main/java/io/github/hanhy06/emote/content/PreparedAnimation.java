package io.github.hanhy06.emote.content;

import io.github.hanhy06.emote.api.EmoteCallback;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.EmoteLoadException;
import net.minecraft.nbt.NbtOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import static io.github.hanhy06.emote.api.animation.EmoteAnimation.*;
import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import org.jspecify.annotations.Nullable;
import io.github.hanhy06.emote.molang.MolangEngine;
import io.github.hanhy06.emote.molang.MolangQueryCatalog;
import io.github.hanhy06.emote.skin.SkinBinding;
import io.github.hanhy06.emote.skin.SkinBindingCompiler;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import java.nio.file.Path;
import java.util.*;

public final class PreparedAnimation implements PreparedEmote {
    private static final SkinBindingCompiler SKIN_BINDING_COMPILER = new SkinBindingCompiler();
    private final List<SkinBinding> skinBindings;
    private final EmoteAnimation model;
    private final List<String> nodeOrder;
    private final Map<String, MolangEngine.CompiledExpression> expressions;
    private final Map<String, Matrix4f> defaultMatrices;
    private final int displayEntityCount;
    private final Path sourcePath;
    private final Map<String, Map<String, DisplayData>> displayContents;

    private PreparedAnimation(LoadedAnimation source, Map<String, Map<String, DisplayData>> displayContents) {
        this.sourcePath = source.sourcePath();
        this.model = source.model();
        this.skinBindings = List.copyOf(SKIN_BINDING_COMPILER.compile(this.model));
        this.nodeOrder = nodeOrder(this.model.nodes());
        this.expressions = compileExpressions(this.model);
        Map<String, Matrix4f> matrices = new HashMap<>();
        this.model.nodes().forEach((id, node) -> matrices.put(id, baseMatrix(node)));
        this.defaultMatrices = Map.copyOf(matrices);
        this.displayContents = Map.copyOf(displayContents);
        this.displayEntityCount = displayContents.values().stream().mapToInt(Map::size).sum();
        requireSupportedPlayback();
    }

    public static PreparedAnimation prepare(LoadedAnimation loaded) throws EmoteLoadException {
        return prepare(loaded, EmoteMod.SERVER.registryAccess());
    }

    public static PreparedAnimation prepare(LoadedAnimation loaded, HolderLookup.Provider registries) throws EmoteLoadException {
        Objects.requireNonNull(loaded, "loaded");
        Objects.requireNonNull(registries, "registries");
        if (loaded.model().timeline().duration() > 12000) throw new EmoteLoadException(loaded.sourcePath(), "$.animation.duration", "must not exceed 12000 ticks");
        try {
            return new PreparedAnimation(loaded, resolveDisplayContents(loaded, registries));
        } catch (IllegalArgumentException exception) {
            String message = exception.getMessage();
            int separator = message == null ? -1 : message.indexOf(' ');
            String path = message != null && message.startsWith("$.") ? (separator < 0 ? message : message.substring(0, separator)).replaceAll(":$", "") : "$";
            String detail = message != null && message.startsWith("$.") && separator >= 0 ? message.substring(separator + 1) : message;
            throw new EmoteLoadException(loaded.sourcePath(), path, detail, exception);
        }
    }

    private static Map<String, Map<String, DisplayData>> resolveDisplayContents(LoadedAnimation loaded, HolderLookup.Provider registries) throws EmoteLoadException {
        Map<String, Map<String, DisplayData>> content = new LinkedHashMap<>();
        for (var entry : loaded.model().nodes().entrySet()) {
            Map<String, DisplayData> attachments = new LinkedHashMap<>();
            for (var attachment : entry.getValue().attachments().entrySet()) {
                String path = "$.nodes." + entry.getKey() + ".attachments." + attachment.getKey();
                Attachment value = attachment.getValue();
                if (value instanceof ExternalAttachment) continue;
                try {
                    DisplayData data = switch (value) {
                        case ItemAttachment item -> new DisplayData.Item(ItemStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), item.itemStackNbt()).getOrThrow(),
                            ItemDisplayContext.CODEC.parse(JsonOps.INSTANCE, new JsonPrimitive(item.itemDisplay())).getOrThrow());
                        case BlockAttachment block -> new DisplayData.Block(BlockState.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), block.blockStateNbt()).getOrThrow());
                        case TextAttachment text -> new DisplayData.Text(ComponentSerialization.CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE), text.text()).getOrThrow());
                        case SkinAttachment ignored -> new DisplayData.Item(new ItemStack(Items.PLAYER_HEAD), ItemDisplayContext.NONE);
                        default -> throw new IllegalStateException("Unsupported attachment");
                    };
                    attachments.put(attachment.getKey(), data);
                } catch (RuntimeException exception) {
                    String field = value instanceof ItemAttachment ? "item_stack_snbt" : value instanceof BlockAttachment ? "block_state_snbt" : "text";
                    throw new EmoteLoadException(loaded.sourcePath(), path + "." + field, "value is not valid for Minecraft", exception);
                }
            }
            content.put(entry.getKey(), Map.copyOf(attachments));
        }
        return Map.copyOf(content);
    }


    @Override public Map<String, EmoteAnimation.Node> nodes() { return this.model.nodes(); }
    @Override public Map<String, Map<String, DisplayData>> displayContents() { return this.displayContents; }

    public EmoteAnimation model() {
        return this.model;
    }

    public List<String> nodeOrder() { return this.nodeOrder; }

    public io.github.hanhy06.emote.molang.MolangEngine.CompiledExpression expression(String path) {
        return this.expressions.get(path);
    }

    private static List<String> nodeOrder(Map<String, EmoteAnimation.Node> nodes) {
        List<String> result = new ArrayList<>();
        Set<String> pending = new TreeSet<>(nodes.keySet());
        while (!pending.isEmpty()) {
            List<String> ready = pending.stream().filter(id -> nodes.get(id).parentId() == null || result.contains(nodes.get(id).parentId())).toList();
            if (ready.isEmpty()) throw new IllegalArgumentException("Missing parent or cyclic node hierarchy");
            result.addAll(ready);
            pending.removeAll(ready);
        }
        return List.copyOf(result);
    }

    private static Map<String, io.github.hanhy06.emote.molang.MolangEngine.CompiledExpression> compileExpressions(EmoteAnimation animation) {
        Map<String, io.github.hanhy06.emote.molang.MolangEngine.CompiledExpression> result = new HashMap<>();
        for (var track : animation.timeline().tracks().values()) {
            List<EmoteAnimation.Value> values = new ArrayList<>();
            if (track.driver().value() != null) values.add(track.driver().value());
            for (var key : track.driver().keys()) { values.add(key.pre()); values.add(key.post()); }
            List<EmoteAnimation.ScalarValue> scalars = new ArrayList<>();
            for (var segment : track.driver().segments()) {
                if (segment.previous() != null) values.add(segment.previous());
                if (segment.following() != null) values.add(segment.following());
                if (segment.outTangent() != null) values.add(segment.outTangent());
                if (segment.inTangent() != null) values.add(segment.inTangent());
                for (var handle : segment.handles()) { scalars.add(handle.outValue()); scalars.add(handle.inValue()); }
            }
            for (var value : values) {
                if (value instanceof EmoteAnimation.VectorValue vector) scalars.addAll(vector.components());
                else if (value instanceof EmoteAnimation.MolangVisibility m) scalars.add(new EmoteAnimation.MolangValue(m.source(), m.path()));
                else if (value instanceof EmoteAnimation.MolangNbtValue m) scalars.add(new EmoteAnimation.MolangValue(m.source(), m.path()));
            }
            for (var scalar : scalars) {
                if (scalar instanceof EmoteAnimation.MolangValue m) compileExpression(result, m.source(), m.path(), false);
            }
        }
        if (animation.timeline().clock() != null) compileExpression(result, animation.timeline().clock(), "$.animation.clock.expression", false);
        if (animation.molang().initialize() != null) compileExpression(result, animation.molang().initialize(), "$.animation.programs.initialize", true);
        if (animation.molang().update() != null) compileExpression(result, animation.molang().update(), "$.animation.programs.update", true);
        for (var delay : List.of(animation.settings().playback().startDelay(), animation.settings().playback().loopDelay())) {
            if (delay instanceof EmoteAnimation.MolangValue m) compileExpression(result, m.source(), m.path(), false);
        }
        return Map.copyOf(result);
    }

    private static void compileExpression(Map<String, io.github.hanhy06.emote.molang.MolangEngine.CompiledExpression> result,
                                          String source, String path, boolean allowPersistentWrites) {
        if (result.containsKey(path)) return;
        try {
            var expression = io.github.hanhy06.emote.molang.MolangEngine.INSTANCE.compile(source);
            MolangQueryCatalog.validate(expression, path);
            if (!allowPersistentWrites && expression.assignsPersistentVariables()) throw new IllegalArgumentException(path + " must not assign persistent variables");
            result.put(path, expression);
        } catch (io.github.hanhy06.emote.molang.MolangEngine.MolangCompileException e) {
            throw new IllegalArgumentException(path + " contains invalid Molang", e);
        }
    }

    private void requireSupportedPlayback() {
        var playback = model.settings().playback();
        if (playback.mode() == EmoteAnimation.LoopMode.SERVER_SYNC && (model.timeline().clock() != null || model.molang().update() != null
            || !(playback.startDelay() instanceof EmoteAnimation.ConstantValue) || !(playback.loopDelay() instanceof EmoteAnimation.ConstantValue))) {
            throw new IllegalArgumentException("$.animation.playback: server_sync requires reconstructible clock and state");
        }
        var events = model.timeline().events();
        Map<String, List<EmoteAnimation.Event>> phases = Map.of("start", events.start(), "timeline", events.timeline().stream().map(EmoteAnimation.TimelineEvent::event).toList(), "loop", events.loop(), "stop", events.stop());
        phases.forEach((phase, actions) -> {
            for (int index = 0; index < actions.size(); index++) {
                if (actions.get(index).externalKey() != null) throw new IllegalArgumentException("$.animation.events." + phase + "[" + index + "].action: no resolver for external action " + actions.get(index).externalKey());
            }
        });
        Set<String> unknownOrientations = new HashSet<>();
        for (String id : this.nodeOrder) {
            var node = model.nodes().get(id);
            boolean parentUnknown = node.parentId() != null && unknownOrientations.contains(node.parentId());
            if (parentUnknown && (node.inherit().rotation() == EmoteAnimation.RotationInheritance.ENTITY || !node.inherit().scale())) {
                throw new IllegalArgumentException("$.nodes." + id + ".inherit requires orientation unavailable from a matrix ancestor");
            }
            if (parentUnknown || node.transform().stream().anyMatch(op -> op.op() == EmoteAnimation.OperationType.MATRIX)) unknownOrientations.add(id);
        }
        for (var track : model.timeline().tracks().values()) {
            if (playback.mode() == EmoteAnimation.LoopMode.SERVER_SYNC && track.driver().keys().stream().anyMatch(key -> key.post() instanceof EmoteAnimation.MolangNbtValue)) {
                throw new IllegalArgumentException("$.animation.playback: server_sync cannot reconstruct dynamic NBT");
            }
        }
    }

    public static Matrix4f baseMatrix(EmoteAnimation.Node node) {
        Matrix4f matrix = new Matrix4f();
        for (var operation : node.transform()) matrix.mul(operationMatrix(operation, operation.value().stream().mapToDouble(Double::doubleValue).toArray()));
        return matrix;
    }

    public static Matrix4f operationMatrix(EmoteAnimation.Operation operation, double[] value) {
        Matrix4f matrix = new Matrix4f();
        switch (operation.op()) {
            case TRANSLATE -> matrix.translate((float) value[0], (float) value[1], (float) value[2]);
            case SCALE -> matrix.scale((float) value[0], (float) value[1], (float) value[2]);
            case ROTATE_EULER, ROTATE_QUATERNION -> matrix.rotation(operationRotation(operation, value));
            case MATRIX -> matrix.set((float) value[0], (float) value[4], (float) value[8], (float) value[12],
                (float) value[1], (float) value[5], (float) value[9], (float) value[13],
                (float) value[2], (float) value[6], (float) value[10], (float) value[14],
                (float) value[3], (float) value[7], (float) value[11], (float) value[15]);
        }
        return matrix;
    }

    public static Quaternionf operationRotation(EmoteAnimation.Operation operation, double[] value) {
        if (operation.op() == EmoteAnimation.OperationType.ROTATE_QUATERNION) {
            Quaterniond rotation = new Quaterniond(value[0], value[1], value[2], value[3]);
            if (rotation.lengthSquared() == 0) throw new IllegalArgumentException("Zero quaternion");
            return new Quaternionf(rotation.normalize());
        }
        Quaternionf rotation = new Quaternionf();
        for (char axis : operation.order().name().toCharArray()) {
            float radians = (float) Math.toRadians(value[axis == 'X' ? 0 : axis == 'Y' ? 1 : 2]);
            if (axis == 'X') rotation.rotateX(radians);
            else if (axis == 'Y') rotation.rotateY(radians);
            else rotation.rotateZ(radians);
        }
        return rotation.normalize();
    }

    public String id() {
        return this.model.id().toString();
    }

    public EmoteMetadata metadata() {
        return this.model.metadata();
    }

    @Override
    public boolean standalone() {
        return this.model.settings().standalone();
    }

    public EmotePlayerBehavior playerBehavior() {
        return this.model.settings().player();
    }

    public Path sourcePath() {
        return this.sourcePath;
    }

    public int nodeCount() {
        return model().nodes().size();
    }

    @Override
    public @Nullable Integer duration() {
        return this.model.timeline().clock() == null ? this.model.timeline().duration() : null;
    }

    @Override
    public int cooldown() {
        return this.model.settings().cooldown();
    }

    @Override
    public EmoteAnimation.LoopMode loopMode() {
        return this.model.settings().playback().mode();
    }

    public List<SkinBinding> skinBindings() {
        return this.skinBindings;
    }

    public float rotationDeadzone() { return this.model.settings().rotationDeadzone(); }
    public int displayEntityCount() { return this.displayEntityCount; }
    public List<EmoteCallback> callbacks() { return this.model.callbacks(); }

    public Matrix4fc defaultMatrix(String nodeId) {
        Matrix4f matrix = this.defaultMatrices.get(nodeId);
        if (matrix == null) {
            throw new IllegalStateException("Missing default matrix for node: " + nodeId);
        }
        return matrix;
    }

}
