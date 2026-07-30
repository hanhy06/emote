package io.github.hanhy06.emote.playback;

import com.mojang.math.Transformation;
import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.emote.RegisteredEmote;
import io.github.hanhy06.emote.mixin.BlockDisplayAccessor;
import io.github.hanhy06.emote.mixin.DisplayAccessor;
import io.github.hanhy06.emote.mixin.ItemDisplayAccessor;
import io.github.hanhy06.emote.mixin.TextDisplayAccessor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.ResolutionContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.block.Blocks;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.github.hanhy06.emote.playback.PlaybackNodes.*;

public final class PlaybackEntityController {
    public static final String RUNTIME_TAG = "emote.runtime";

    public PlaybackNodes spawn(ServerPlayer player, RegisteredEmote emote) {
        PlaybackNodes nodes = create(player, emote);
        add(player.level(), nodes);
        return nodes;
    }

    public PlaybackNodes create(ServerPlayer player, RegisteredEmote emote) {
        ServerLevel level = player.level();
        EmoteRootTransform root = EmoteRootTransform.fromPlayer(player);
        LinkedHashMap<String, NodeInstance> instances = new LinkedHashMap<>();
        for (Map.Entry<String, EmoteAnimation.Node> entry : emote.animation().nodes().entrySet()) {
            EmoteAnimation.PreparedDisplayData preparedData = emote.source().preparedDisplayData().get(entry.getKey());
            NodeInstance instance = createNode(level, root, entry.getKey(), entry.getValue(), preparedData);
            instances.put(entry.getKey(), instance);
        }
        return new PlaybackNodes(root, instances);
    }

    public void add(ServerLevel level, PlaybackNodes nodes) {
        try {
            for (NodeInstance node : nodes.nodes().values()) {
                if (!node.isAnchor() && !level.addFreshEntity(node.entity())) {
                    throw new IllegalStateException("Failed to add display entity for node " + node.id());
                }
            }
        } catch (RuntimeException exception) {
            removeEntities(level, nodes.nodes().values());
            throw exception;
        }
    }

    public void remove(ServerLevel level, PlaybackNodes nodes) {
        removeEntities(level, nodes.nodes().values());
    }

    public void setVisible(NodeInstance node, boolean visible) {
        if (node.isAnchor()) {
            return;
        }
        switch (node.displayContent()) {
            case ItemContent(ItemStack itemStack) ->
                ((ItemDisplayAccessor)node.entity()).emote$setItemStack(visible ? itemStack : ItemStack.EMPTY);
            case BlockContent(var blockState) ->
                ((BlockDisplayAccessor)node.entity()).emote$setBlockState(
                    visible ? blockState : Blocks.AIR.defaultBlockState()
                );
            case TextContent(Component text) ->
                ((TextDisplayAccessor)node.entity()).emote$setText(visible ? text : Component.empty());
            case null -> {
            }
        }
    }

    public void applyTransformation(
        PlaybackNodes playbackNodes,
        NodeInstance node,
        EmoteAnimation.Matrix matrix,
        int interpolationDurationTicks
    ) {
        applyTransformation(
            node,
            new Transformation(playbackNodes.root().displayMatrix(matrix)),
            interpolationDurationTicks
        );
    }

    public void applyTransformation(
        NodeInstance node,
        Transformation transformation,
        int interpolationDurationTicks
    ) {
        if (node.isAnchor()) {
            return;
        }
        applyTransformation(node.entity(), transformation, interpolationDurationTicks);
    }

    public void resetNode(PlaybackNodes playbackNodes, NodeInstance node) {
        applyTransformation(playbackNodes, node, node.node().defaultMatrix(), 0);
        if (!node.isAnchor()) {
            setVisible(node, initialVisibility(node.node()));
        }
    }

    private NodeInstance createNode(
        ServerLevel level,
        EmoteRootTransform root,
        String nodeId,
        EmoteAnimation.Node node,
        EmoteAnimation.PreparedDisplayData preparedData
    ) {
        if (node instanceof EmoteAnimation.AnchorNode) {
            return new NodeInstance(nodeId, node, null, null);
        }

        Display entity = createDisplay(level, node);
        TypedEntityData.of(entity.getType(), entityNbt(node)).loadInto(entity);
        entity.setPos(root.position());
        entity.setDeltaMovement(0.0D, 0.0D, 0.0D);
        entity.setYRot(0.0F);
        entity.setXRot(0.0F);
        entity.addTag(RUNTIME_TAG);

        DisplayContent content = applyRuntimeData(entity, root, node, requirePreparedData(nodeId, preparedData));
        boolean visible = initialVisibility(node);
        NodeInstance instance = new NodeInstance(nodeId, node, entity, content);
        if (!visible) {
            setVisible(instance, false);
        }
        return instance;
    }

    private Display createDisplay(ServerLevel level, EmoteAnimation.Node node) {
        Display display = switch (node) {
            case EmoteAnimation.ItemNode ignored ->
                EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.COMMAND);
            case EmoteAnimation.BlockNode ignored ->
                EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
            case EmoteAnimation.TextNode ignored ->
                EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
            case EmoteAnimation.AnchorNode ignored ->
                throw new IllegalArgumentException("Anchor nodes do not have display entities");
        };
        if (display == null) {
            throw new IllegalStateException("Failed to create display entity");
        }
        return display;
    }

    private DisplayContent applyRuntimeData(
        Display entity,
        EmoteRootTransform root,
        EmoteAnimation.Node node,
        EmoteAnimation.PreparedDisplayData preparedData
    ) {
        applyTransformation(entity, new Transformation(root.displayMatrix(node.defaultMatrix())), 0);
        return switch (preparedData) {
            case EmoteAnimation.PreparedItemData(ItemStack itemStack, var itemDisplay) -> {
                ItemDisplayAccessor accessor = (ItemDisplayAccessor)entity;
                accessor.emote$setItemStack(itemStack);
                accessor.emote$setItemTransform(itemDisplay);
                yield new ItemContent(itemStack);
            }
            case EmoteAnimation.PreparedBlockData(var blockState) -> {
                ((BlockDisplayAccessor)entity).emote$setBlockState(blockState);
                yield new BlockContent(blockState);
            }
            case EmoteAnimation.PreparedTextData(Component unresolvedText) -> {
                Component text = resolveText((Display.TextDisplay)entity, unresolvedText);
                ((TextDisplayAccessor)entity).emote$setText(text);
                yield new TextContent(text);
            }
        };
    }

    private void applyTransformation(Display entity, Transformation transformation, int interpolationDurationTicks) {
        DisplayAccessor accessor = (DisplayAccessor)entity;
        accessor.emote$setTransformation(transformation);
        accessor.emote$setTransformationInterpolationDuration(interpolationDurationTicks);
        accessor.emote$setTransformationInterpolationDelay(0);
    }

    private EmoteAnimation.PreparedDisplayData requirePreparedData(
        String nodeId,
        EmoteAnimation.PreparedDisplayData preparedData
    ) {
        if (preparedData == null) {
            throw new IllegalStateException("Display node was not prepared during reload: " + nodeId);
        }
        return preparedData;
    }

    private Component resolveText(Display.TextDisplay entity, Component text) {
        try {
            var source = entity.createCommandSourceStackForNameResolution((ServerLevel)entity.level())
                .withPermission(LevelBasedPermissionSet.GAMEMASTER);
            return ComponentUtils.resolve(ResolutionContext.create(source), text);
        } catch (Exception exception) {
            Emote.LOGGER.warn("Failed to resolve display entity text {}", text, exception);
            return Component.empty();
        }
    }

    private CompoundTag entityNbt(EmoteAnimation.Node node) {
        if (node instanceof EmoteAnimation.ItemNode itemNode) {
            return itemNode.entityNbt();
        }
        if (node instanceof EmoteAnimation.BlockNode blockNode) {
            return blockNode.entityNbt();
        }
        if (node instanceof EmoteAnimation.TextNode textNode) {
            return textNode.entityNbt();
        }
        return new CompoundTag();
    }

    private boolean initialVisibility(EmoteAnimation.Node node) {
        if (node instanceof EmoteAnimation.ItemNode itemNode) {
            return itemNode.visible();
        }
        if (node instanceof EmoteAnimation.BlockNode blockNode) {
            return blockNode.visible();
        }
        if (node instanceof EmoteAnimation.TextNode textNode) {
            return textNode.visible();
        }
        return true;
    }

    private void removeEntities(ServerLevel level, Collection<NodeInstance> nodes) {
        for (NodeInstance node : nodes) {
            if (node.entity() != null && !node.entity().isRemoved()) {
                node.entity().kill(level);
            }
        }
    }
}
