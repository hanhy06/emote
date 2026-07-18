package io.github.hanhy06.emote.playback;

import com.mojang.math.Transformation;
import com.mojang.serialization.JsonOps;
import io.github.hanhy06.emote.animation.EmoteAnimation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static io.github.hanhy06.emote.playback.PlaybackNodes.*;

public final class PlaybackEntityController {
    private static final String RUNTIME_TAG = "emote.runtime";
    private static final Map<String, ItemDisplayContext> ITEM_DISPLAY_CONTEXTS = Arrays.stream(ItemDisplayContext.values())
        .collect(Collectors.toUnmodifiableMap(ItemDisplayContext::getSerializedName, context -> context));

    public PlaybackNodes spawn(ServerPlayer player, EmoteAnimation animation) {
        PlaybackNodes nodes = create(player, animation);
        add(player.level(), nodes);
        return nodes;
    }

    public PlaybackNodes create(ServerPlayer player, EmoteAnimation animation) {
        ServerLevel level = player.level();
        EmoteRootTransform root = EmoteRootTransform.fromPlayer(player);
        LinkedHashMap<String, NodeInstance> instances = new LinkedHashMap<>();
        for (Map.Entry<String, EmoteAnimation.Node> entry : animation.nodes().entrySet()) {
            NodeInstance instance = createNode(level, root, entry.getKey(), entry.getValue());
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
        CompoundTag data = new CompoundTag();
        var registryOps = node.entity().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        if (node.displayContent() instanceof ItemContent itemContent) {
            data.store("item", ItemStack.CODEC, registryOps, visible ? itemContent.itemStack() : ItemStack.EMPTY);
        } else if (node.displayContent() instanceof BlockContent blockContent) {
            data.store("block_state", BlockState.CODEC, registryOps, visible ? blockContent.blockState() : Blocks.AIR.defaultBlockState());
        } else if (node.displayContent() instanceof TextContent textContent) {
            data.store("text", ComponentSerialization.CODEC, registryOps, visible ? textContent.text() : Component.empty());
        }
        TypedEntityData.of(node.entity().getType(), data).loadInto(node.entity());
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
        CompoundTag data = new CompoundTag();
        data.store("transformation", Transformation.EXTENDED_CODEC, transformation);
        data.putInt("interpolation_duration", interpolationDurationTicks);
        data.putInt("start_interpolation", 0);
        TypedEntityData.of(node.entity().getType(), data).loadInto(node.entity());
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
        EmoteAnimation.Node node
    ) {
        if (node instanceof EmoteAnimation.AnchorNode) {
            return new NodeInstance(nodeId, node, null, null);
        }

        Display entity = createDisplay(level, node);
        TypedEntityData.of(entity.getType(), entityNbt(node)).loadInto(entity);
        entity.setPos(root.position());
        entity.setYRot(0.0F);
        entity.setXRot(0.0F);
        entity.addTag(RUNTIME_TAG);

        DisplayContent content = applyRuntimeData(entity, root, node);
        boolean visible = initialVisibility(node);
        NodeInstance instance = new NodeInstance(nodeId, node, entity, content);
        if (!visible) {
            setVisible(instance, false);
        }
        return instance;
    }

    private Display createDisplay(ServerLevel level, EmoteAnimation.Node node) {
        Display display;
        if (node instanceof EmoteAnimation.ItemNode) {
            display = EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        } else if (node instanceof EmoteAnimation.BlockNode) {
            display = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        } else if (node instanceof EmoteAnimation.TextNode) {
            display = EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        } else {
            throw new IllegalArgumentException("Unsupported display node: " + node.getClass().getName());
        }
        if (display == null) {
            throw new IllegalStateException("Failed to create display entity");
        }
        return display;
    }

    private DisplayContent applyRuntimeData(Display entity, EmoteRootTransform root, EmoteAnimation.Node node) {
        var registryOps = entity.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        CompoundTag data = new CompoundTag();
        data.store("transformation", Transformation.EXTENDED_CODEC, new Transformation(root.displayMatrix(node.defaultMatrix())));
        data.putInt("interpolation_duration", 0);
        data.putInt("start_interpolation", 0);

        DisplayContent content;
        if (node instanceof EmoteAnimation.ItemNode itemNode) {
            ItemStack itemStack = ItemStack.CODEC.parse(registryOps, itemNode.itemStackNbt()).getOrThrow();
            ItemDisplayContext context = ITEM_DISPLAY_CONTEXTS.get(itemNode.itemDisplay());
            if (context == null) {
                throw new IllegalArgumentException("Unsupported item display context: " + itemNode.itemDisplay());
            }
            data.store("item", ItemStack.CODEC, registryOps, itemStack);
            data.store("item_display", ItemDisplayContext.CODEC, context);
            content = new ItemContent(itemStack);
        } else if (node instanceof EmoteAnimation.BlockNode blockNode) {
            BlockState blockState = BlockState.CODEC.parse(registryOps, blockNode.blockStateNbt()).getOrThrow();
            data.store("block_state", BlockState.CODEC, registryOps, blockState);
            content = new BlockContent(blockState);
        } else if (node instanceof EmoteAnimation.TextNode textNode) {
            var jsonOps = entity.registryAccess().createSerializationContext(JsonOps.INSTANCE);
            Component text = ComponentSerialization.CODEC.parse(jsonOps, textNode.text()).getOrThrow();
            data.store("text", ComponentSerialization.CODEC, registryOps, text);
            content = new TextContent(text);
        } else {
            throw new IllegalArgumentException("Unsupported display node: " + node.getClass().getName());
        }
        TypedEntityData.of(entity.getType(), data).loadInto(entity);
        return content;
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
