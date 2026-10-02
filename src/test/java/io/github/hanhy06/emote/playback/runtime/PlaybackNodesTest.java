package io.github.hanhy06.emote.playback.runtime;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.content.PreparedAnimation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlaybackNodesTest {
    @Test
    void keepsViewYawInsideThresholdAndFollowsOnlyTheExcess() {
        PlaybackNodes nodes = new PlaybackNodes(
            RootTransform.create(Vec3.ZERO, 0.0F),
            Map.of()
        );

        assertEquals(0.0F, nodes.updateViewYaw(40.0F, 50.0F), 0.0001F);
        assertEquals(10.0F, nodes.updateViewYaw(60.0F, 50.0F), 0.0001F);
        assertEquals(30.0F, nodes.updateViewYaw(80.0F, 50.0F), 0.0001F);
    }

    @Test
    void appliesViewYawThresholdAcrossDegreeWrap() {
        PlaybackNodes nodes = new PlaybackNodes(
            RootTransform.create(Vec3.ZERO, 170.0F),
            Map.of()
        );

        assertEquals(170.0F, nodes.updateViewYaw(-170.0F, 50.0F), 0.0001F);
        assertEquals(-150.0F, nodes.updateViewYaw(-100.0F, 50.0F), 0.0001F);
    }

    @Test
    void updatesDisplayRotationOnlyWhenPackedYawChanges() {
        PlaybackNodes nodes = new PlaybackNodes(
            RootTransform.create(Vec3.ZERO, 0.0F),
            Map.of()
        );
        PlaybackEntityController controller = new PlaybackEntityController();

        assertFalse(controller.updateViewRotation(nodes, 40.0F, 50.0F));
        assertTrue(controller.updateViewRotation(nodes, 60.0F, 50.0F));
        assertFalse(controller.updateViewRotation(nodes, 60.5F, 50.0F));
    }

    @Test
    void followsViewYawWithoutDeadzone() {
        PlaybackNodes nodes = new PlaybackNodes(
            RootTransform.create(Vec3.ZERO, 0.0F),
            Map.of()
        );

        assertEquals(40.0F, nodes.updateViewYaw(40.0F, 0.0F), 0.0001F);
        assertEquals(-30.0F, nodes.updateViewYaw(-30.0F, 0.0F), 0.0001F);
    }

    @Test
    void usesOneTickInterpolationWithoutRotationDeadzone() {
        assertEquals(1, PlaybackEntityController.positionRotationInterpolationTicks(0.0F));
        assertEquals(3, PlaybackEntityController.positionRotationInterpolationTicks(50.0F));
    }

    @Test
    void itemNodeKeepsReplacementStackForVisibilityRestores() {
        EmoteAnimation.ItemNode itemNode = new EmoteAnimation.ItemNode(
            true,
            null,
            EmoteAnimation.LocalTransform.IDENTITY,
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

    @Test
    void countsDisplayNodesOnceWithoutIncludingAnchors() {
        EmoteAnimation.ItemNode itemNode = new EmoteAnimation.ItemNode(
            true,
            null,
            EmoteAnimation.LocalTransform.IDENTITY,
            new CompoundTag(),
            new CompoundTag(),
            "none",
            null
        );
        EmoteAnimation.AnchorNode anchorNode = new EmoteAnimation.AnchorNode(
            null,
            EmoteAnimation.LocalTransform.IDENTITY
        );
        PlaybackNodes nodes = new PlaybackNodes(
            RootTransform.create(Vec3.ZERO, 0.0F),
            Map.of(
                "item", new PlaybackNodes.NodeInstance("item", itemNode, null, null),
                "anchor", new PlaybackNodes.NodeInstance("anchor", anchorNode, null, null)
            )
        );

        assertEquals(1, nodes.displayEntityCount());
    }

    @Test
    void singleRootTracksViewYaw() {
        RootTransform root = RootTransform.create(Vec3.ZERO, 0.0F);
        PlaybackNodes nodes = new PlaybackNodes(root, Map.of());
        assertSame(root, nodes.root());
        nodes.updateViewYaw(90.0F, 50.0F);
        assertEquals(40.0F, nodes.orientationYaw());
    }

    @Test
    void movesRootWhilePreservingYaw() {
        PlaybackNodes nodes = new PlaybackNodes(RootTransform.create(new Vec3(10, 64, 20), 30), Map.of());
        assertTrue(nodes.moveSceneTo(new Vec3(13, 65, 24)));
        assertEquals(new Vec3(13, 65, 24), nodes.root().position());
        assertEquals(30.0F, nodes.root().yaw());
        assertFalse(nodes.moveSceneTo(new Vec3(13, 65, 24)));
    }

    @Test
    void createsEquivalentIndependentTransformations() {
        RootTransform root = RootTransform.create(Vec3.ZERO, 0.0F);
        PlaybackNodes nodes = new PlaybackNodes(root, Map.of());
        var transform = PreparedAnimation.PreparedTransform.create(EmoteAnimation.LocalTransform.IDENTITY, false);
        var first = nodes.displayTransformation(transform);
        var second = nodes.displayTransformation(transform);
        assertNotSame(first, second);
        assertEquals(first.getMatrix(), second.getMatrix());
        assertEquals(root.displayTransformation(transform).getMatrix(), first.getMatrix());
    }
}
