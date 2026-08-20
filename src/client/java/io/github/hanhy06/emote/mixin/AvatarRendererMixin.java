package io.github.hanhy06.emote.mixin;

import io.github.hanhy06.emote.EmoteClientMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AvatarRenderer.class)
abstract class AvatarRendererMixin {
    @Inject(
        method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
        at = @At("TAIL")
    )
    private void emote$hideLocalPlayerEquipment(
        Avatar entity,
        AvatarRenderState state,
        float partialTicks,
        CallbackInfo callbackInfo
    ) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || entity != player || !EmoteClientMod.shouldHideLocalPlayerEquipment()) {
            return;
        }

        state.leftHandItemState.clear();
        state.rightHandItemState.clear();
        state.leftHandItemStack = ItemStack.EMPTY;
        state.rightHandItemStack = ItemStack.EMPTY;
        state.headEquipment = ItemStack.EMPTY;
        state.chestEquipment = ItemStack.EMPTY;
        state.legsEquipment = ItemStack.EMPTY;
        state.feetEquipment = ItemStack.EMPTY;
        state.heldOnHead.clear();
    }
}
