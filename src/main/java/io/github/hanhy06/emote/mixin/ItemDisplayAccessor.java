package io.github.hanhy06.emote.mixin;

import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Display.ItemDisplay.class)
public interface ItemDisplayAccessor {
    @Invoker("setItemStack")
    void emote$setItemStack(ItemStack itemStack);

    @Invoker("setItemTransform")
    void emote$setItemTransform(ItemDisplayContext context);
}
