package io.github.hanhy06.emote.skin;

import io.github.hanhy06.emote.animation.EmoteAnimation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmoteSkinPartFactoryTest {
    @Test
    void usesExplicitOrderAndLocalYScaleForLimbSegments() {
        LinkedHashMap<String, EmoteAnimation.Node> nodes = new LinkedHashMap<>();
        nodes.put("outer", itemNode(3.0D, EmoteAnimation.SkinPart.LEFT_ARM, 1));
        nodes.put("inner", itemNode(1.0D, EmoteAnimation.SkinPart.LEFT_ARM, 0));
        nodes.put("head", itemNode(1.0D, EmoteAnimation.SkinPart.HEAD, 0));

        List<EmoteSkinPart> parts = new EmoteSkinPartFactory().create(animation(nodes));

        assertEquals(new PlayerSkinSegment(0, 3), find(parts, "inner").skinSegment());
        assertEquals(new PlayerSkinSegment(3, 12), find(parts, "outer").skinSegment());
        assertEquals(PlayerSkinSegment.FULL, find(parts, "head").skinSegment());
    }

    private EmoteSkinPart find(List<EmoteSkinPart> parts, String nodeId) {
        return parts.stream().filter(part -> part.nodeId().equals(nodeId)).findFirst().orElseThrow();
    }

    private EmoteAnimation.ItemNode itemNode(double yScale, EmoteAnimation.SkinPart part, int order) {
        return new EmoteAnimation.ItemNode(
            true,
            new EmoteAnimation.Matrix(List.of(
                1.0D, 0.0D, 0.0D, 0.0D,
                0.0D, yScale, 0.0D, 0.0D,
                0.0D, 0.0D, 1.0D, 0.0D,
                0.0D, 0.0D, 0.0D, 1.0D
            )),
            new CompoundTag(),
            new CompoundTag(),
            "none",
            new EmoteAnimation.Skin(part, order)
        );
    }

    private EmoteAnimation animation(Map<String, EmoteAnimation.Node> nodes) {
        return new EmoteAnimation(
            Identifier.parse("test:skin"),
            new EmoteAnimation.Metadata("Skin", "Skin", false),
            nodes,
            new EmoteAnimation.Timeline(1, EmoteAnimation.LoopMode.ONCE, 0, List.of(), EmoteAnimation.Events.empty())
        );
    }
}
