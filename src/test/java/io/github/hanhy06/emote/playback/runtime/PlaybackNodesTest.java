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
    void restoresOnlyPatchedNbtFieldsAndKeepsUpdatedSkinDefaults() {
        var node = new PlaybackNodes.NodeInstance("display", new EmoteAnimation.AnchorNode(
            EmoteAnimation.NodeSpace.SCENE, null, EmoteAnimation.LocalTransform.IDENTITY), null, null);
        CompoundTag baseline = new CompoundTag();
        baseline.putBoolean("Glowing", false);
        CompoundTag item = new CompoundTag();
        item.putString("skin", "original");
        baseline.put("item", item);
        node.setInitialEntityData(baseline);
        CompoundTag patch = new CompoundTag();
        patch.putBoolean("Glowing", true);
        patch.putString("CustomName", "temporary");
        patch.put("item", new CompoundTag());
        node.recordNbtFields(patch);
        CompoundTag skinnedItem = new CompoundTag();
        skinnedItem.putString("skin", "baked");
        node.setInitialItem(skinnedItem);
        CompoundTag current = patch.copy();
        current.putString("Pos", "current position");
        current.putString("Rotation", "current rotation");
        assertEquals(java.util.Set.of("Glowing", "CustomName", "item"), node.restoreNbtFields(current));
        assertFalse(current.getBooleanOr("Glowing", true));
        assertFalse(current.contains("CustomName"));
        assertEquals(skinnedItem, current.get("item"));
        assertEquals("current position", current.getStringOr("Pos", ""));
        assertEquals("current rotation", current.getStringOr("Rotation", ""));
        assertFalse(node.hasModifiedNbt());
    }

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

        assertFalse(controller.updateViewRotation(nodes, 40.0F, 50.0F));
        assertTrue(controller.updateViewRotation(nodes, 60.0F, 50.0F));
        assertFalse(controller.updateViewRotation(nodes, 60.5F, 50.0F));
    }

    @Test
    void followsViewYawWithoutDeadzone() {
        PlaybackNodes nodes = new PlaybackNodes(
            SceneRootResolver.single(RootTransform.create(Vec3.ZERO, 0.0F)),
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
            EmoteAnimation.NodeSpace.SCENE,
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
            EmoteAnimation.NodeSpace.SCENE,
            null,
            EmoteAnimation.LocalTransform.IDENTITY,
            new CompoundTag(),
            new CompoundTag(),
            "none",
            null
        );
        EmoteAnimation.AnchorNode anchorNode = new EmoteAnimation.AnchorNode(
            EmoteAnimation.NodeSpace.SCENE,
            null,
            EmoteAnimation.LocalTransform.IDENTITY
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
            null,
            EmoteAnimation.LocalTransform.IDENTITY
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
        assertEquals(40.0F, nodes.orientationYaw(EmoteAnimation.NodeSpace.INITIATOR));
        assertEquals(-140.0F, nodes.orientationYaw(EmoteAnimation.NodeSpace.PARTNER));
    }

    @Test
    void nodeOriginsFollowTheSameYawDeltaAsDisplaysInEverySpace() {
        RootTransform scene = RootTransform.create(Vec3.ZERO, 30.0F);
        PlaybackNodes nodes = new PlaybackNodes(Map.of(
            EmoteAnimation.NodeSpace.SCENE, scene,
            EmoteAnimation.NodeSpace.INITIATOR, RootTransform.create(Vec3.ZERO, -45.0F),
            EmoteAnimation.NodeSpace.PARTNER, RootTransform.create(Vec3.ZERO, 180.0F)
        ), Map.of());
        nodes.updateViewYaw(120.0F, 0.0F);
        for (EmoteAnimation.NodeSpace space : EmoteAnimation.NodeSpace.values()) {
            RootTransform root = nodes.root(space);
            var transform = root.displayMatrix(new org.joml.Matrix4f().translate(1, 0, 0));
            var displayed = new org.joml.Matrix4f().rotateY((float) Math.toRadians(-scene.relativeYaw(nodes.viewYaw())))
                .mul(transform).transformPosition(new org.joml.Vector3f());
            var origin = root.worldMatrix(nodes.orientationYaw(space), transform).transformPosition(new org.joml.Vector3f());
            assertTrue(displayed.distance(origin) < 1.0E-5F, space.toString());
        }
    }

    @Test
    void movesEveryNodeSpaceWithTheSceneWhilePreservingRelativePlacement() {
        RootTransform scene = RootTransform.create(new Vec3(10.0D, 64.0D, 20.0D), 30.0F);
        RootTransform partner = RootTransform.create(new Vec3(12.0D, 64.0D, 19.0D), -45.0F);
        PlaybackNodes nodes = new PlaybackNodes(
            Map.of(
                EmoteAnimation.NodeSpace.SCENE, scene,
                EmoteAnimation.NodeSpace.INITIATOR, scene,
                EmoteAnimation.NodeSpace.PARTNER, partner
            ),
            Map.of()
        );

        assertTrue(nodes.moveSceneTo(new Vec3(13.0D, 65.0D, 24.0D)));

        assertEquals(new Vec3(13.0D, 65.0D, 24.0D), nodes.root().position());
        assertEquals(new Vec3(13.0D, 65.0D, 24.0D), nodes.root(EmoteAnimation.NodeSpace.INITIATOR).position());
        assertEquals(new Vec3(15.0D, 65.0D, 23.0D), nodes.root(EmoteAnimation.NodeSpace.PARTNER).position());
        assertEquals(30.0F, nodes.root().yaw());
        assertEquals(-45.0F, nodes.root(EmoteAnimation.NodeSpace.PARTNER).yaw());
        assertFalse(nodes.moveSceneTo(new Vec3(13.0D, 65.0D, 24.0D)));
    }

    @Test
    void createsEquivalentPreparedTransformationsPerNodeSpace() {
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
        PreparedAnimation.PreparedTransform transform = PreparedAnimation.PreparedTransform.create(EmoteAnimation.LocalTransform.IDENTITY, false);

        var firstScene = nodes.displayTransformation(EmoteAnimation.NodeSpace.SCENE, transform);
        var secondScene = nodes.displayTransformation(EmoteAnimation.NodeSpace.SCENE, transform);
        var partnerResult = nodes.displayTransformation(EmoteAnimation.NodeSpace.PARTNER, transform);

        assertNotSame(firstScene, secondScene);
        assertNotSame(firstScene, partnerResult);
        assertEquals(firstScene.getMatrix(), secondScene.getMatrix());
        assertEquals(scene.displayTransformation(transform).getMatrix(), firstScene.getMatrix());
        assertEquals(partner.displayTransformation(transform).getMatrix(), partnerResult.getMatrix());
    }

}
