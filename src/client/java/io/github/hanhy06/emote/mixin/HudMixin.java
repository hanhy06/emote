package io.github.hanhy06.emote.mixin;

import io.github.hanhy06.emote.client.WheelScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Hud;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
abstract class HudMixin {
    // World rendering reads the vanilla HUD visibility flag even when the wheel skips the HUD.
    @Inject(
        method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/renderer/state/gui/GuiRenderState;isHudHidden:Z",
            opcode = Opcodes.PUTFIELD,
            shift = At.Shift.AFTER
        ),
        cancellable = true
    )
    private void emote$hideWheelBackgroundHud(CallbackInfo callbackInfo) {
        if (Minecraft.getInstance().gui.screen() instanceof WheelScreen) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = {"extractDebugOverlay", "extractSavingIndicator"}, at = @At("HEAD"), cancellable = true)
    private void emote$hideWheelBackgroundIndicators(CallbackInfo callbackInfo) {
        if (Minecraft.getInstance().gui.screen() instanceof WheelScreen) {
            callbackInfo.cancel();
        }
    }
}
