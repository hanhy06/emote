package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.animation.EmoteAnimation;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.Objects;

public record JsonPlaybackNodes(EmoteRootTransform root, Map<String, NodeInstance> nodes) {
    public JsonPlaybackNodes {
        Objects.requireNonNull(root, "root");
        nodes = Map.copyOf(nodes);
    }

    public static final class NodeInstance {
        private final String id;
        private final EmoteAnimation.Node definition;
        private final Display entity;
        private final DisplayContent displayContent;
        private EmoteAnimation.Matrix currentMatrix;
        private boolean visible;

        public NodeInstance(
            String id,
            EmoteAnimation.Node definition,
            Display entity,
            DisplayContent displayContent,
            boolean visible
        ) {
            this.id = Objects.requireNonNull(id, "id");
            this.definition = Objects.requireNonNull(definition, "definition");
            this.entity = entity;
            this.displayContent = displayContent;
            this.currentMatrix = definition.defaultMatrix();
            this.visible = visible;
        }

        public String id() {
            return this.id;
        }

        public EmoteAnimation.Node definition() {
            return this.definition;
        }

        public Display entity() {
            return this.entity;
        }

        public DisplayContent displayContent() {
            return this.displayContent;
        }

        public EmoteAnimation.Matrix currentMatrix() {
            return this.currentMatrix;
        }

        public void setCurrentMatrix(EmoteAnimation.Matrix currentMatrix) {
            this.currentMatrix = Objects.requireNonNull(currentMatrix, "currentMatrix");
        }

        public boolean visible() {
            return this.visible;
        }

        public void setVisible(boolean visible) {
            this.visible = visible;
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
