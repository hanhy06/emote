package io.github.hanhy06.emote.mixin;

import io.github.hanhy06.emote.EmoteClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
abstract class ClientLivingEntityMixin {
    @Inject(method = "updateInvisibilityStatus", at = @At("TAIL"))
    private void emote$maintainLocalPlayerVisibility(CallbackInfo callbackInfo) {
        if ((Object) this == Minecraft.getInstance().player && EmoteClient.isPlaybackActive()) {
            ((LivingEntity) (Object) this).setInvisible(true);
        }
    }
}
