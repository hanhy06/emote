package io.github.hanhy06.emote.mixin.accessor;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Display.TextDisplay.class)
public interface TextDisplayAccessor {
    @Invoker("getText")
    Component emote$getText();

    @Invoker("setText")
    void emote$setText(Component text);
}
