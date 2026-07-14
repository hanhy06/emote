package io.github.hanhy06.emote.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.hanhy06.emote.EmoteClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandLayer.class)
abstract class ItemInHandLayerMixin {
    @Inject(method = "submit", at = @At("HEAD"), cancellable = true)
    private void emote$hideLocalPlayerHeldItems(
        PoseStack poseStack,
        SubmitNodeCollector submitNodeCollector,
        int lightCoords,
        ArmedEntityRenderState state,
        float yRot,
        float xRot,
        CallbackInfo callbackInfo
    ) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null
            && state instanceof AvatarRenderState avatarState
            && avatarState.id == player.getId()
            && EmoteClient.isPlaybackActive()) {
            callbackInfo.cancel();
        }
    }
}
