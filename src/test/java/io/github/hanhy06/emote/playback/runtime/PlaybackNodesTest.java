package io.github.hanhy06.emote.playback.runtime;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.content.CompiledTimeline;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlaybackNodesTest {
    @Test
    void keepsViewYawInsideThresholdAndFollowsOnlyTheExcess() {
        PlaybackNodes nodes = new PlaybackNodes(
            SceneRootResolver.single(RootTransform.create(Vec3.ZERO, 0.0F)),
            Map.of()
        );

        assertEquals(0.0F, nodes.updateViewYaw(40.0F, 50.0F), 0.0001F);
        assertEquals(10.0F, nodes.updateViewYaw(60.0F, 50.0F), 0.0001F);
        assertEquals(30.0F, nodes.updateViewYaw(80.0F, 50.0F), 0.0001F);
    }

    @Test
    void appliesViewYawThresholdAcrossDegreeWrap() {
        PlaybackNodes nodes = new PlaybackNodes(
            SceneRootResolver.single(RootTransform.create(Vec3.ZERO, 170.0F)),
            Map.of()
        );

        assertEquals(170.0F, nodes.updateViewYaw(-170.0F, 50.0F), 0.0001F);
        assertEquals(-150.0F, nodes.updateViewYaw(-100.0F, 50.0F), 0.0001F);
    }

    @Test
    void updatesDisplayRotationOnlyWhenPackedYawChanges() {
        PlaybackNodes nodes = new PlaybackNodes(
            SceneRootResolver.single(RootTransform.create(Vec3.ZERO, 0.0F)),
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
            EmoteAnimation.NodeSpace.SCENE,
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

    @Test
    void countsDisplayNodesOnceWithoutIncludingAnchors() {
        EmoteAnimation.ItemNode itemNode = new EmoteAnimation.ItemNode(
            true,
            EmoteAnimation.NodeSpace.SCENE,
            identityMatrix(),
            new CompoundTag(),
            new CompoundTag(),
            "none",
            null
        );
        EmoteAnimation.AnchorNode anchorNode = new EmoteAnimation.AnchorNode(
            EmoteAnimation.NodeSpace.SCENE,
            identityMatrix()
        );
        PlaybackNodes nodes = new PlaybackNodes(
            SceneRootResolver.single(RootTransform.create(Vec3.ZERO, 0.0F)),
            Map.of(
                "item", new PlaybackNodes.NodeInstance("item", itemNode, null, null),
                "anchor", new PlaybackNodes.NodeInstance("anchor", anchorNode, null, null)
            )
        );

        assertEquals(1, nodes.displayEntityCount());
    }

    @Test
    void masksPartnerVisibilityUntilPartnerSpaceIsActivated() {
        EmoteAnimation.AnchorNode partnerNode = new EmoteAnimation.AnchorNode(
            EmoteAnimation.NodeSpace.PARTNER,
            identityMatrix()
        );
        PlaybackNodes nodes = new PlaybackNodes(
            SceneRootResolver.single(RootTransform.create(Vec3.ZERO, 0.0F)),
            Map.of("partner", new PlaybackNodes.NodeInstance("partner", partnerNode, null, null))
        );

        assertFalse(nodes.requestVisibility("partner", true));
        assertFalse(nodes.effectiveVisibility("partner"));

        nodes.activateSpace(EmoteAnimation.NodeSpace.PARTNER);

        assertTrue(nodes.effectiveVisibility("partner"));
    }

    @Test
    void resolvesEachNodeSpaceAgainstItsOwnRoot() {
        RootTransform scene = RootTransform.create(Vec3.ZERO, 0.0F);
        RootTransform partner = RootTransform.create(new Vec3(1.2D, 0.0D, 0.0D), 180.0F);
        PlaybackNodes nodes = new PlaybackNodes(
            Map.of(
                EmoteAnimation.NodeSpace.SCENE, scene,
                EmoteAnimation.NodeSpace.INITIATOR, scene,
                EmoteAnimation.NodeSpace.PARTNER, partner
            ),
            Map.of()
        );

        assertSame(scene, nodes.root(EmoteAnimation.NodeSpace.INITIATOR));
        assertSame(partner, nodes.root(EmoteAnimation.NodeSpace.PARTNER));
        nodes.updateViewYaw(90.0F, 50.0F);
        assertEquals(40.0F, nodes.orientationYaw(EmoteAnimation.NodeSpace.SCENE));
        assertEquals(180.0F, nodes.orientationYaw(EmoteAnimation.NodeSpace.PARTNER));
    }

    @Test
    void cachesPreparedTransformationsPerNodeSpace() {
        RootTransform scene = RootTransform.create(Vec3.ZERO, 0.0F);
        RootTransform partner = RootTransform.create(new Vec3(1.2D, 0.0D, 0.0D), 180.0F);
        PlaybackNodes nodes = new PlaybackNodes(
            Map.of(
                EmoteAnimation.NodeSpace.SCENE, scene,
                EmoteAnimation.NodeSpace.INITIATOR, scene,
                EmoteAnimation.NodeSpace.PARTNER, partner
            ),
            Map.of()
        );
        CompiledTimeline.PreparedTransform transform = CompiledTimeline.PreparedTransform.create(identityMatrix(), false);

        var firstScene = nodes.displayTransformation(EmoteAnimation.NodeSpace.SCENE, transform);
        var secondScene = nodes.displayTransformation(EmoteAnimation.NodeSpace.SCENE, transform);
        var partnerResult = nodes.displayTransformation(EmoteAnimation.NodeSpace.PARTNER, transform);

        assertSame(firstScene, secondScene);
        assertNotSame(firstScene, partnerResult);
        assertEquals(scene.displayTransformation(transform), firstScene);
        assertEquals(partner.displayTransformation(transform), partnerResult);
    }

    private EmoteAnimation.Matrix identityMatrix() {
        return new EmoteAnimation.Matrix(List.of(
            1.0D, 0.0D, 0.0D, 0.0D,
            0.0D, 1.0D, 0.0D, 0.0D,
            0.0D, 0.0D, 1.0D, 0.0D,
            0.0D, 0.0D, 0.0D, 1.0D
        ));
    }
}
