package io.github.hanhy06.emote.content.loader;

import io.github.hanhy06.emote.skin.model.PlayerSkinPart;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class AnimationContentResolverTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void rejectsSkinMetadataOnNonPlayerHeadItem() {
        EmoteAnimation.ItemNode itemNode = itemNode(new EmoteAnimation.Skin(
            PlayerSkinPart.HEAD,
            0
        ));

        EmoteAnimationLoadException exception = assertThrows(
            EmoteAnimationLoadException.class,
            () -> AnimationContentResolver.validateSkinTarget(
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
        assertDoesNotThrow(() -> AnimationContentResolver.validateSkinTarget(
            Path.of("plain-item.json"),
            "$.nodes.item",
            itemNode(null),
            ItemStack.EMPTY
        ));
    }

    private EmoteAnimation.ItemNode itemNode(EmoteAnimation.Skin skin) {
        return new EmoteAnimation.ItemNode(
            true,
            null,
            EmoteAnimation.LocalTransform.IDENTITY,
            new CompoundTag(),
            new CompoundTag(),
            "none",
            skin
        );
    }
}
