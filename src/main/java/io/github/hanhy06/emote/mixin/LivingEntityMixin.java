package io.github.hanhy06.emote.mixin;

import io.github.hanhy06.emote.playback.PlaybackEquipmentSyncCallback;
import io.github.hanhy06.emote.playback.PlaybackVisibilityCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {
    @Inject(method = "updateInvisibilityStatus", at = @At("TAIL"))
    private void emote$afterInvisibilityUpdate(CallbackInfo callbackInfo) {
        if ((Object) this instanceof ServerPlayer player) {
            PlaybackVisibilityCallback.EVENT.invoker().afterInvisibilityUpdate(player);
        }
    }

    @Inject(method = "handleEquipmentChanges", at = @At("TAIL"))
    private void emote$afterEquipmentSync(Map<EquipmentSlot, ItemStack> changedItems, CallbackInfo callbackInfo) {
        if ((Object) this instanceof ServerPlayer player) {
            PlaybackEquipmentSyncCallback.EVENT.invoker().afterEquipmentSync(player, changedItems);
        }
    }
}
