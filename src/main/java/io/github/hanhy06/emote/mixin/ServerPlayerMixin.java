package io.github.hanhy06.emote.mixin;

import io.github.hanhy06.emote.playback.PlaybackHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
abstract class ServerPlayerMixin {
    @Inject(
        method = "setGameMode(Lnet/minecraft/world/level/GameType;)Z",
        at = @At("RETURN")
    )
    private void emote$afterGameModeChange(GameType mode, CallbackInfoReturnable<Boolean> callbackInfo) {
        if (callbackInfo.getReturnValueZ()) {
            ServerPlayer player = (ServerPlayer) (Object) this;
            PlaybackHooks.INTERRUPTION.invoker().interruptPlayback(player);
        }
    }
}
