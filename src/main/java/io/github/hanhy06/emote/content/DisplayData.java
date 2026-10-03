package io.github.hanhy06.emote.content;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

public sealed interface DisplayData permits DisplayData.Item, DisplayData.Block, DisplayData.Text {
    record Item(ItemStack itemStack, ItemDisplayContext itemDisplay) implements DisplayData {
        public Item {
            itemStack = Objects.requireNonNull(itemStack, "itemStack").copy();
            Objects.requireNonNull(itemDisplay, "itemDisplay");
        }

        @Override
        public ItemStack itemStack() {
            return this.itemStack.copy();
        }
    }

    record Block(BlockState blockState) implements DisplayData {
        public Block {
            Objects.requireNonNull(blockState, "blockState");
        }
    }

    record Text(Component text) implements DisplayData {
        public Text {
            text = Objects.requireNonNull(text, "text").copy();
        }

        @Override
        public Component text() {
            return this.text.copy();
        }
    }
}
