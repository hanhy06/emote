package io.github.hanhy06.emote.mixin;

import io.github.hanhy06.emote.playback.PlaybackEntityController;
import io.github.hanhy06.emote.playback.PlaybackHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
abstract class EntityMixin {
    @Inject(method = "shouldBeSaved", at = @At("HEAD"), cancellable = true)
    private void emote$preventRuntimeDisplaySave(CallbackInfoReturnable<Boolean> callbackInfo) {
        Entity entity = (Entity) (Object) this;
        if (entity instanceof Display
            && entity.entityTags().contains(PlaybackEntityController.RUNTIME_TAG)) {
            callbackInfo.setReturnValue(false);
        }
    }

    @Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z", at = @At("RETURN"))
    private void emote$afterPlayerMount(
        Entity entityToRide,
        boolean force,
        boolean sendEventAndTriggers,
        CallbackInfoReturnable<Boolean> callbackInfo
    ) {
        Entity entity = (Entity) (Object) this;
        if (callbackInfo.getReturnValueZ() && entity instanceof ServerPlayer player) {
            PlaybackHooks.INTERRUPTION.invoker().interruptPlayback(player);
        }
    }
}
