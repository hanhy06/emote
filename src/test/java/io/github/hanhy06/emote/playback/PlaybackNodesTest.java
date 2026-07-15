package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.animation.EmoteAnimation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotSame;

class PlaybackNodesTest {
    @Test
    void itemNodeKeepsReplacementStackForVisibilityRestores() {
        EmoteAnimation.ItemNode itemNode = new EmoteAnimation.ItemNode(
            true,
            new EmoteAnimation.Matrix(List.of(
                1.0D, 0.0D, 0.0D, 0.0D,
                0.0D, 1.0D, 0.0D, 0.0D,
                0.0D, 0.0D, 1.0D, 0.0D,
                0.0D, 0.0D, 0.0D, 1.0D
            )),
            new CompoundTag(),
            new CompoundTag(),
            "none",
            null
        );
        PlaybackNodes.NodeInstance node = new PlaybackNodes.NodeInstance(
            "item",
            itemNode,
            null,
            new PlaybackNodes.ItemContent(ItemStack.EMPTY),
            true
        );

        PlaybackNodes.DisplayContent originalContent = node.displayContent();

        node.setItemStack(ItemStack.EMPTY);

        assertNotSame(originalContent, node.displayContent());
    }
}
