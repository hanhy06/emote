package io.github.hanhy06.emote.animation;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;
import io.github.hanhy06.emote.api.ParticipantRole;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnimationServerPreparerTest {
    private static final EmoteAnimation.Matrix IDENTITY = new EmoteAnimation.Matrix(List.of(
        1.0D, 0.0D, 0.0D, 0.0D,
        0.0D, 1.0D, 0.0D, 0.0D,
        0.0D, 0.0D, 1.0D, 0.0D,
        0.0D, 0.0D, 0.0D, 1.0D
    ));

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void rejectsSkinMetadataOnNonPlayerHeadItem() {
        EmoteAnimation.ItemNode itemNode = itemNode(new EmoteAnimation.Skin(
            ParticipantRole.INITIATOR,
            EmoteAnimation.SkinPart.HEAD,
            0
        ));

        EmoteAnimationLoadException exception = assertThrows(
            EmoteAnimationLoadException.class,
            () -> AnimationServerPreparer.validateSkinTarget(
                Path.of("invalid-skin.json"),
                "$.nodes.head",
                itemNode,
                ItemStack.EMPTY
            )
        );

        assertEquals("$.nodes.head.skin", exception.fieldPath());
    }

    @Test
    void acceptsNonPlayerHeadItemWithoutSkinMetadata() {
        assertDoesNotThrow(() -> AnimationServerPreparer.validateSkinTarget(
            Path.of("plain-item.json"),
            "$.nodes.item",
            itemNode(null),
            ItemStack.EMPTY
        ));
    }

    private EmoteAnimation.ItemNode itemNode(EmoteAnimation.Skin skin) {
        return new EmoteAnimation.ItemNode(
            true,
            EmoteAnimation.NodeSpace.INITIATOR,
            IDENTITY,
            new CompoundTag(),
            new CompoundTag(),
            "none",
            skin
        );
    }
}
