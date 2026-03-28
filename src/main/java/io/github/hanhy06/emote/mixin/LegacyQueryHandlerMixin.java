package io.github.hanhy06.emote.mixin;

import io.github.hanhy06.emote.Emote;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.server.network.LegacyQueryHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LegacyQueryHandler.class)
public class LegacyQueryHandlerMixin {
	@Inject(method = "channelRead", at = @At("HEAD"), cancellable = true)
	private void emote$handleHttpRequest(ChannelHandlerContext context, Object message, CallbackInfo callbackInfo) {
		if (!(message instanceof ByteBuf input)) {
			return;
		}

		if (!Emote.getPlayerSkinManager().handleHttpRequest(context, input)) {
			return;
		}

		input.release();
		callbackInfo.cancel();
	}
}
