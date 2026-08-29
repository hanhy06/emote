package io.github.hanhy06.emote.skin;

import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.ParticipantRole;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.skin.model.PlayerSkinSegment;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkinBindingCompilerTest {
    @Test
    void usesExplicitOrderAndLocalYScaleForLimbSegments() {
        LinkedHashMap<String, EmoteAnimation.Node> nodes = new LinkedHashMap<>();
        nodes.put("outer", itemNode(3.0D, EmoteAnimation.SkinPart.LEFT_ARM, 1));
        nodes.put("inner", itemNode(1.0D, EmoteAnimation.SkinPart.LEFT_ARM, 0));
        nodes.put("head", itemNode(1.0D, EmoteAnimation.SkinPart.HEAD, 0));

        List<SkinBinding> bindings = new SkinBindingCompiler().compile(animation(nodes));

        assertEquals(new PlayerSkinSegment(0, 3), find(bindings, "inner").region().skinSegment());
        assertEquals(new PlayerSkinSegment(3, 12), find(bindings, "outer").region().skinSegment());
        assertEquals(PlayerSkinSegment.FULL, find(bindings, "head").region().skinSegment());
        assertEquals(ParticipantRole.INITIATOR, find(bindings, "head").participant());
    }

    @Test
    void preservesFourAndEightPixelRigidSlices() {
        LinkedHashMap<String, EmoteAnimation.Node> nodes = new LinkedHashMap<>();
        nodes.put("upper", itemNode(0.5D, EmoteAnimation.SkinPart.RIGHT_ARM, 0));
        nodes.put("lower", itemNode(1.0D, EmoteAnimation.SkinPart.RIGHT_ARM, 1));

        List<SkinBinding> bindings = new SkinBindingCompiler().compile(animation(nodes));

        assertEquals(new PlayerSkinSegment(0, 4), find(bindings, "upper").region().skinSegment());
        assertEquals(new PlayerSkinSegment(4, 12), find(bindings, "lower").region().skinSegment());
    }

    @Test
    void preservesFourTwoTwoFourPixelJointSlices() {
        LinkedHashMap<String, EmoteAnimation.Node> nodes = new LinkedHashMap<>();
        nodes.put("upper", itemNode(0.5D, EmoteAnimation.SkinPart.LEFT_LEG, 0));
        nodes.put("upper_joint", itemNode(0.25D, EmoteAnimation.SkinPart.LEFT_LEG, 1));
        nodes.put("lower_joint", itemNode(0.25D, EmoteAnimation.SkinPart.LEFT_LEG, 2));
        nodes.put("lower", itemNode(0.5D, EmoteAnimation.SkinPart.LEFT_LEG, 3));

        List<SkinBinding> bindings = new SkinBindingCompiler().compile(animation(nodes));

        assertEquals(new PlayerSkinSegment(0, 4), find(bindings, "upper").region().skinSegment());
        assertEquals(new PlayerSkinSegment(4, 6), find(bindings, "upper_joint").region().skinSegment());
        assertEquals(new PlayerSkinSegment(6, 8), find(bindings, "lower_joint").region().skinSegment());
        assertEquals(new PlayerSkinSegment(8, 12), find(bindings, "lower").region().skinSegment());
    }

    @Test
    void sharesJointSegmentsWithDuplicateFillerNodes() {
        LinkedHashMap<String, EmoteAnimation.Node> nodes = new LinkedHashMap<>();
        nodes.put("upper", itemNode(0.5D, EmoteAnimation.SkinPart.RIGHT_ARM, 0));
        nodes.put("upper_joint", itemNode(0.25D, EmoteAnimation.SkinPart.RIGHT_ARM, 1));
        nodes.put("upper_joint_fill", itemNode(0.2625D, EmoteAnimation.SkinPart.RIGHT_ARM, 1));
        nodes.put("lower_joint_fill", itemNode(0.26875D, EmoteAnimation.SkinPart.RIGHT_ARM, 2));
        nodes.put("lower_joint", itemNode(0.25D, EmoteAnimation.SkinPart.RIGHT_ARM, 2));
        nodes.put("lower", itemNode(0.5D, EmoteAnimation.SkinPart.RIGHT_ARM, 3));

        List<SkinBinding> bindings = new SkinBindingCompiler().compile(animation(nodes));

        assertEquals(new PlayerSkinSegment(0, 4), find(bindings, "upper").region().skinSegment());
        assertEquals(new PlayerSkinSegment(4, 6), find(bindings, "upper_joint").region().skinSegment());
        assertEquals(new PlayerSkinSegment(4, 6), find(bindings, "upper_joint_fill").region().skinSegment());
        assertEquals(new PlayerSkinSegment(6, 8), find(bindings, "lower_joint_fill").region().skinSegment());
        assertEquals(new PlayerSkinSegment(6, 8), find(bindings, "lower_joint").region().skinSegment());
        assertEquals(new PlayerSkinSegment(8, 12), find(bindings, "lower").region().skinSegment());
    }

    private SkinBinding find(List<SkinBinding> bindings, String nodeId) {
        return bindings.stream().filter(binding -> binding.nodeId().equals(nodeId)).findFirst().orElseThrow();
    }

    private EmoteAnimation.ItemNode itemNode(double yScale, EmoteAnimation.SkinPart part, int order) {
        return new EmoteAnimation.ItemNode(
            true,
            EmoteAnimation.NodeSpace.INITIATOR,
            null,
            new EmoteAnimation.LocalTransform(
                EmoteAnimation.Vec3.ZERO,
                EmoteAnimation.Vec3.ZERO,
                new EmoteAnimation.Vec3(1.0D, yScale, 1.0D)
            ),
            new CompoundTag(),
            new EmoteAnimation.FixedItemSource(new CompoundTag()),
            "none",
            new EmoteAnimation.Skin(ParticipantRole.INITIATOR, part, order)
        );
    }

    private EmoteAnimation animation(Map<String, EmoteAnimation.Node> nodes) {
        return new EmoteAnimation(
            Identifier.parse("test:skin"),
            new EmoteMetadata("Skin", "Skin"),
            new EmoteAnimation.Settings(true, 0, 50.0F, EmotePlayerBehavior.createDefault(), new EmoteAnimation.PlaybackSettings(EmoteAnimation.LoopMode.ONCE, 0)),
            EmoteAnimation.MolangPrograms.empty(),
            nodes,
            new EmoteAnimation.Timeline(1, Map.of(), EmoteAnimation.Events.empty())
        );
    }
}
