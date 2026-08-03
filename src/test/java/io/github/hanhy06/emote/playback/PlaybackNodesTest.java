package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackNodesTest {
    @Test
    void keepsViewYawInsideThresholdAndFollowsOnlyTheExcess() {
        PlaybackNodes nodes = new PlaybackNodes(
            EmoteRootTransform.create(Vec3.ZERO, 0.0F),
            Map.of()
        );

        assertEquals(0.0F, nodes.updateViewYaw(40.0F, 50.0F), 0.0001F);
        assertEquals(10.0F, nodes.updateViewYaw(60.0F, 50.0F), 0.0001F);
        assertEquals(30.0F, nodes.updateViewYaw(80.0F, 50.0F), 0.0001F);
    }

    @Test
    void appliesViewYawThresholdAcrossDegreeWrap() {
        PlaybackNodes nodes = new PlaybackNodes(
            EmoteRootTransform.create(Vec3.ZERO, 170.0F),
            Map.of()
        );

        assertEquals(170.0F, nodes.updateViewYaw(-170.0F, 50.0F), 0.0001F);
        assertEquals(-150.0F, nodes.updateViewYaw(-100.0F, 50.0F), 0.0001F);
    }

    @Test
    void updatesDisplayRotationOnlyWhenPackedYawChanges() {
        PlaybackNodes nodes = new PlaybackNodes(
            EmoteRootTransform.create(Vec3.ZERO, 0.0F),
            Map.of()
        );
        PlaybackEntityController controller = new PlaybackEntityController();

        assertFalse(controller.updateViewRotation(nodes, 40.0F));
        assertTrue(controller.updateViewRotation(nodes, 60.0F));
        assertFalse(controller.updateViewRotation(nodes, 60.5F));
    }

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
            new PlaybackNodes.ItemContent(ItemStack.EMPTY)
        );

        PlaybackNodes.DisplayContent originalContent = node.displayContent();

        node.setItemStack(ItemStack.EMPTY);

        assertNotSame(originalContent, node.displayContent());
    }
}
