package io.github.hanhy06.emote.mixin;

import io.github.hanhy06.emote.playback.PlaybackMountCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
abstract class EntityMixin {
    @Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z", at = @At("RETURN"))
    private void emote$afterPlayerMount(
        Entity entityToRide,
        boolean force,
        boolean sendEventAndTriggers,
        CallbackInfoReturnable<Boolean> callbackInfo
    ) {
        if (callbackInfo.getReturnValueZ() && (Object) this instanceof ServerPlayer player) {
            PlaybackMountCallback.EVENT.invoker().afterMount(player);
        }
    }
}
