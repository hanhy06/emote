package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.animation.EmoteAnimation;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.Objects;

public record PlaybackNodes(EmoteRootTransform root, Map<String, NodeInstance> nodes) {
    public PlaybackNodes {
        Objects.requireNonNull(root, "root");
        nodes = Map.copyOf(nodes);
    }

    public static final class NodeInstance {
        private final String id;
        private final EmoteAnimation.Node node;
        private final Display entity;
        private DisplayContent displayContent;

        public NodeInstance(
            String id,
            EmoteAnimation.Node node,
            Display entity,
            DisplayContent displayContent
        ) {
            this.id = Objects.requireNonNull(id, "id");
            this.node = Objects.requireNonNull(node, "node");
            this.entity = entity;
            this.displayContent = displayContent;
        }

        public String id() {
            return this.id;
        }

        public EmoteAnimation.Node node() {
            return this.node;
        }

        public Display entity() {
            return this.entity;
        }

        public DisplayContent displayContent() {
            return this.displayContent;
        }

        public void setItemStack(ItemStack itemStack) {
            if (!(this.displayContent instanceof ItemContent)) {
                throw new IllegalStateException("Node is not an item display: " + this.id);
            }
            this.displayContent = new ItemContent(Objects.requireNonNull(itemStack, "itemStack"));
        }

        public boolean isAnchor() {
            return this.entity == null;
        }
    }

    public sealed interface DisplayContent permits ItemContent, BlockContent, TextContent {
    }

    public record ItemContent(ItemStack itemStack) implements DisplayContent {
        public ItemContent {
            itemStack = itemStack.copy();
        }

        @Override
        public ItemStack itemStack() {
            return this.itemStack.copy();
        }
    }

    public record BlockContent(BlockState blockState) implements DisplayContent {
        public BlockContent {
            Objects.requireNonNull(blockState, "blockState");
        }
    }

    public record TextContent(Component text) implements DisplayContent {
        public TextContent {
            Objects.requireNonNull(text, "text");
        }
    }
}
