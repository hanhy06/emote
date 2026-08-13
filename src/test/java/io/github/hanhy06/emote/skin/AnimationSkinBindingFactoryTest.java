package io.github.hanhy06.emote.skin;

import io.github.hanhy06.emote.skin.SkinBinding;
import io.github.hanhy06.emote.skin.SkinBindingCompiler;
import io.github.hanhy06.emote.skin.model.PlayerSkinSegment;
import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.ParticipantRole;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.api.ParticipantRole;
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

        List<SkinBinding> parts = new SkinBindingCompiler().create(animation(nodes));

        assertEquals(new PlayerSkinSegment(0, 3), find(parts, "inner").region().skinSegment());
        assertEquals(new PlayerSkinSegment(3, 12), find(parts, "outer").region().skinSegment());
        assertEquals(PlayerSkinSegment.FULL, find(parts, "head").region().skinSegment());
        assertEquals(ParticipantRole.INITIATOR, find(parts, "head").participant());
    }

    private SkinBinding find(List<SkinBinding> parts, String nodeId) {
        return parts.stream().filter(part -> part.nodeId().equals(nodeId)).findFirst().orElseThrow();
    }

    private EmoteAnimation.ItemNode itemNode(double yScale, EmoteAnimation.SkinPart part, int order) {
        return new EmoteAnimation.ItemNode(
            true,
            EmoteAnimation.NodeSpace.INITIATOR,
            new EmoteAnimation.Matrix(List.of(
                1.0D, 0.0D, 0.0D, 0.0D,
                0.0D, yScale, 0.0D, 0.0D,
                0.0D, 0.0D, 1.0D, 0.0D,
                0.0D, 0.0D, 0.0D, 1.0D
            )),
            new CompoundTag(),
            new CompoundTag(),
            "none",
            new EmoteAnimation.Skin(ParticipantRole.INITIATOR, part, order)
        );
    }

    private EmoteAnimation animation(Map<String, EmoteAnimation.Node> nodes) {
        return new EmoteAnimation(
            Identifier.parse("test:skin"),
            new EmoteMetadata("Skin", "Skin"),
            new EmoteAnimation.Settings(true, 0, EmotePlayerBehavior.createDefault(), new EmoteAnimation.PlaybackSettings(EmoteAnimation.LoopMode.ONCE, 0)),
            nodes,
            new EmoteAnimation.Timeline(1, List.of(), EmoteAnimation.Events.empty())
        );
    }
}
