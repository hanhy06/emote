package io.github.hanhy06.emote.content;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

public sealed interface PreparedDisplayData permits PreparedDisplayData.Item, PreparedDisplayData.Block, PreparedDisplayData.Text {
    record Item(PreparedItem source, ItemDisplayContext itemDisplay) implements PreparedDisplayData {
        public Item {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(itemDisplay, "itemDisplay");
        }
    }

    sealed interface PreparedItem permits FixedItem, ParticipantHandItem {
    }

    record FixedItem(ItemStack itemStack) implements PreparedItem {
        public FixedItem {
            itemStack = Objects.requireNonNull(itemStack, "itemStack").copy();
        }

        @Override
        public ItemStack itemStack() {
            return this.itemStack.copy();
        }
    }

    record ParticipantHandItem(HumanoidArm arm) implements PreparedItem {
        public ParticipantHandItem {
            Objects.requireNonNull(arm, "arm");
        }
    }

    record Block(BlockState blockState) implements PreparedDisplayData {
        public Block {
            Objects.requireNonNull(blockState, "blockState");
        }
    }

    record Text(Component text) implements PreparedDisplayData {
        public Text {
            text = Objects.requireNonNull(text, "text").copy();
        }

        @Override
        public Component text() {
            return this.text.copy();
        }
    }
}
