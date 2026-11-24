package io.github.hanhy06.emote.mixin;

import io.github.hanhy06.emote.client.WheelScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
abstract class HudMixin {
    @Inject(
        method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void emote$hideWheelBackgroundHud(CallbackInfo callbackInfo) {
        if (Minecraft.getInstance().screen instanceof WheelScreen) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = {"extractDebugOverlay", "extractSavingIndicator"}, at = @At("HEAD"), cancellable = true)
    private void emote$hideWheelBackgroundIndicators(CallbackInfo callbackInfo) {
        if (Minecraft.getInstance().screen instanceof WheelScreen) {
            callbackInfo.cancel();
        }
    }
}
