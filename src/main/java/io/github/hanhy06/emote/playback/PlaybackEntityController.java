package io.github.hanhy06.emote.playback;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.math.Transformation;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.content.CompiledTimeline;
import io.github.hanhy06.emote.content.PreparedDisplayData;
import io.github.hanhy06.emote.content.PreparedEmote;
import io.github.hanhy06.emote.minecraft.BlockDisplayAccessor;
import io.github.hanhy06.emote.minecraft.DisplayAccessor;
import io.github.hanhy06.emote.minecraft.ItemDisplayAccessor;
import io.github.hanhy06.emote.minecraft.TextDisplayAccessor;
import io.github.hanhy06.emote.skin.PlayerHeadProfileFactory;
import io.github.hanhy06.emote.skin.SkinBinding;
import io.github.hanhy06.emote.skin.model.PreparedPlayerSkin;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.ResolutionContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.github.hanhy06.emote.playback.PlaybackNodes.*;

public final class PlaybackEntityController {
    void applySkin(PlaybackNodes nodes, Collection<SkinBinding> bindings, PreparedPlayerSkin skin) {
        if (skin == null || bindings.isEmpty()) {
            return;
        }
        for (SkinBinding binding : bindings) {
            NodeInstance node = nodes.nodes().get(binding.nodeId());
            if (node == null) {
                continue;
            }
            String textureUrl = skin.findTextureUrl(binding.region());
            if (textureUrl == null
                || !(node.entity() instanceof Display.ItemDisplay itemDisplay)
                || !(node.displayContent() instanceof ItemContent(ItemStack itemStack))
                || !itemStack.is(Items.PLAYER_HEAD)) {
                continue;
            }
            ItemStack profileStack = itemStack.copy();
            profileStack.set(DataComponents.PROFILE, PlayerHeadProfileFactory.createProfile(textureUrl));
            node.setItemStack(profileStack);
            SlotAccess itemSlot = itemDisplay.getSlot(0);
            if (!itemSlot.get().isEmpty()) {
                itemSlot.set(profileStack);
            }
        }
    }

    public static final String RUNTIME_TAG = "emote.runtime";
    private static final int VIEW_ROTATION_INTERPOLATION_TICKS = 3;
    private static final float VIEW_ROTATION_THRESHOLD_DEGREES = 50.0F;

    public PlaybackNodes create(ServerPlayer player, PreparedEmote emote) {
        return create(player.level(), RootTransform.fromPlayer(player), emote);
    }

    PlaybackNodes create(ServerLevel level, RootTransform root, PreparedEmote emote) {
        return create(level, SceneRootResolver.single(root), emote);
    }

    PlaybackNodes create(ServerLevel level, Map<EmoteAnimation.NodeSpace, RootTransform> spaces, PreparedEmote emote) {
        LinkedHashMap<String, NodeInstance> instances = new LinkedHashMap<>();
        for (Map.Entry<String, EmoteAnimation.Node> entry : emote.animation().nodes().entrySet()) {
            PreparedDisplayData preparedData = emote.source().preparedDisplayData().get(entry.getKey());
            RootTransform nodeRoot = spaces.get(entry.getValue().space());
            if (nodeRoot == null) {
                throw new IllegalArgumentException("Missing root for node space " + entry.getValue().space());
            }
            NodeInstance instance = createNode(level, nodeRoot, entry.getKey(), entry.getValue(), preparedData);
            instances.put(entry.getKey(), instance);
        }
        return new PlaybackNodes(spaces, instances);
    }

    PlaybackNodes create(ServerLevel level, Vec3 position, float yaw, PreparedEmote emote) {
        return create(level, RootTransform.create(position, yaw), emote);
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

    public boolean updateViewRotation(PlaybackNodes nodes, float currentYaw) {
        float previousRelativeYaw = nodes.root().relativeYaw(nodes.viewYaw());
        float viewYaw = nodes.updateViewYaw(currentYaw, VIEW_ROTATION_THRESHOLD_DEGREES);
        float relativeYaw = nodes.root().relativeYaw(viewYaw);
        if (Mth.packDegrees(previousRelativeYaw) == Mth.packDegrees(relativeYaw)) {
            return false;
        }
        for (NodeInstance node : nodes.nodes().values()) {
            if (!node.isAnchor()) {
                node.entity().setYRot(relativeYaw);
            }
        }
        return true;
    }

    public void setVisible(NodeInstance node, boolean visible) {
        if (node.isAnchor()) {
            return;
        }
        switch (node.displayContent()) {
            case ItemContent(ItemStack itemStack) ->
                ((ItemDisplayAccessor) node.entity()).emote$setItemStack(visible ? itemStack : ItemStack.EMPTY);
            case BlockContent(var blockState) -> ((BlockDisplayAccessor) node.entity()).emote$setBlockState(
                visible ? blockState : Blocks.AIR.defaultBlockState()
            );
            case TextContent(Component text) ->
                ((TextDisplayAccessor) node.entity()).emote$setText(visible ? text : Component.empty());
            case null -> {
            }
        }
    }

    void activateSpace(PlaybackNodes nodes, EmoteAnimation.NodeSpace space) {
        nodes.activateSpace(space);
        nodes.nodes().forEach((nodeId, node) -> {
            if (node.node().space() == space) {
                setVisible(node, nodes.effectiveVisibility(nodeId));
            }
        });
    }

    void applyTransformation(
        PlaybackNodes playbackNodes,
        NodeInstance node,
        CompiledTimeline.PreparedTransform transform,
        int interpolationDurationTicks
    ) {
        if (node.isAnchor()) {
            return;
        }
        applyTransformation(
            node,
            playbackNodes.displayTransformation(node.node().space(), transform),
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

    private NodeInstance createNode(
        ServerLevel level,
        RootTransform root,
        String nodeId,
        EmoteAnimation.Node node,
        PreparedDisplayData preparedData
    ) {
        if (node instanceof EmoteAnimation.AnchorNode) {
            return new NodeInstance(nodeId, node, null, null);
        }

        Display entity = createDisplay(level, node);
        TypedEntityData.of(entity.getType(), node.entityNbt()).loadInto(entity);
        ((DisplayAccessor) entity).emote$setPosRotInterpolationDuration(VIEW_ROTATION_INTERPOLATION_TICKS);
        entity.setPos(root.position());
        entity.setDeltaMovement(0.0D, 0.0D, 0.0D);
        entity.setYRot(0.0F);
        entity.setXRot(0.0F);
        entity.addTag(RUNTIME_TAG);

        DisplayContent content = applyRuntimeData(entity, requirePreparedData(nodeId, preparedData));
        return new NodeInstance(nodeId, node, entity, content);
    }

    private Display createDisplay(ServerLevel level, EmoteAnimation.Node node) {
        Display display = switch (node) {
            case EmoteAnimation.ItemNode ignored -> EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.COMMAND);
            case EmoteAnimation.BlockNode ignored -> EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
            case EmoteAnimation.TextNode ignored -> EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
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
        PreparedDisplayData preparedData
    ) {
        return switch (preparedData) {
            case PreparedDisplayData.Item(ItemStack itemStack, var itemDisplay) -> {
                ItemDisplayAccessor accessor = (ItemDisplayAccessor) entity;
                accessor.emote$setItemStack(itemStack);
                accessor.emote$setItemTransform(itemDisplay);
                yield new ItemContent(itemStack);
            }
            case PreparedDisplayData.Block(var blockState) -> {
                ((BlockDisplayAccessor) entity).emote$setBlockState(blockState);
                yield new BlockContent(blockState);
            }
            case PreparedDisplayData.Text(Component unresolvedText) -> {
                Component text = resolveText((Display.TextDisplay) entity, unresolvedText);
                ((TextDisplayAccessor) entity).emote$setText(text);
                yield new TextContent(text);
            }
        };
    }

    private void applyTransformation(Display entity, Transformation transformation, int interpolationDurationTicks) {
        DisplayAccessor accessor = (DisplayAccessor) entity;
        accessor.emote$setTransformation(transformation);
        accessor.emote$setTransformationInterpolationDuration(interpolationDurationTicks);
        accessor.emote$setTransformationInterpolationDelay(0);
    }

    private PreparedDisplayData requirePreparedData(
        String nodeId,
        PreparedDisplayData preparedData
    ) {
        if (preparedData == null) {
            throw new IllegalStateException("Display node was not prepared during reload: " + nodeId);
        }
        return preparedData;
    }

    private Component resolveText(Display.TextDisplay entity, Component text) {
        try {
            var source = entity.createCommandSourceStackForNameResolution((ServerLevel) entity.level())
                .withPermission(LevelBasedPermissionSet.GAMEMASTER);
            return ComponentUtils.resolve(ResolutionContext.create(source), text);
        } catch (CommandSyntaxException exception) {
            throw new IllegalStateException("Failed to resolve display entity text", exception);
        }
    }

    private void removeEntities(ServerLevel level, Collection<NodeInstance> nodes) {
        for (NodeInstance node : nodes) {
            if (node.entity() != null && !node.entity().isRemoved()) {
                node.entity().kill(level);
            }
        }
    }
}
