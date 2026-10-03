package io.github.hanhy06.emote.api.animation;

import com.google.gson.JsonPrimitive;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmoteAnimationTest {
    @Test
    void nodeNbtRemainsUnchangedAfterInputsAndAccessorResultsAreModified() {
        CompoundTag entity = new CompoundTag();
        entity.putString("value", "original");
        CompoundTag content = entity.copy();
        var item = new EmoteAnimation.ItemNode(true, null, EmoteAnimation.LocalTransform.IDENTITY, entity, content, "none", null);
        var block = new EmoteAnimation.BlockNode(true, null, EmoteAnimation.LocalTransform.IDENTITY, entity, content);
        var text = new EmoteAnimation.TextNode(true, null, EmoteAnimation.LocalTransform.IDENTITY, entity, new JsonPrimitive("text"));
        CompoundTag expected = entity.copy();

        entity.putString("value", "input changed");
        content.putString("value", "input changed");
        item.entityNbt().putString("value", "accessor changed");
        item.itemStackNbt().putString("value", "accessor changed");
        block.entityNbt().putString("value", "accessor changed");
        block.blockStateNbt().putString("value", "accessor changed");
        text.entityNbt().putString("value", "accessor changed");

        assertEquals(expected, item.entityNbt());
        assertEquals(expected, item.itemStackNbt());
        assertEquals(expected, block.entityNbt());
        assertEquals(expected, block.blockStateNbt());
        assertEquals(expected, text.entityNbt());
    }
}
