package io.github.hanhy06.emote.mixin;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.playback.PlayerPlaybackManager;
import io.github.hanhy06.emote.playback.VirtualDisplayEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
abstract class EntityMixin {
    @Inject(method = "shouldBeSaved", at = @At("HEAD"), cancellable = true)
    private void emote$preventRuntimeEntitySave(CallbackInfoReturnable<Boolean> callbackInfo) {
        Entity entity = (Entity) (Object) this;
        if (entity.entityTags().contains(EmoteMod.RUNTIME_ENTITY_TAG)) {
            callbackInfo.setReturnValue(false);
        }
    }

    @Inject(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/world/entity/Entity;",
        at = @At("HEAD"), cancellable = true)
    private void emote$preventVirtualDisplayDimensionChange(TeleportTransition transition, CallbackInfoReturnable<Entity> callbackInfo) {
        Entity entity = (Entity) (Object) this;
        if (VirtualDisplayEntity.isVirtual(entity) && !transition.newLevel().dimension().equals(entity.level().dimension())) {
            callbackInfo.setReturnValue(null);
        }
    }

    @Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z", at = @At("HEAD"), cancellable = true)
    private void emote$preventVirtualDisplayMount(
        Entity entityToRide,
        boolean force,
        boolean sendEventAndTriggers,
        CallbackInfoReturnable<Boolean> callbackInfo
    ) {
        if (VirtualDisplayEntity.isVirtual((Entity) (Object) this) || VirtualDisplayEntity.isVirtual(entityToRide)) {
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
            PlayerPlaybackManager.INSTANCE.interrupt(player, PlaybackStopReason.MOUNTED);
        }
    }
}
