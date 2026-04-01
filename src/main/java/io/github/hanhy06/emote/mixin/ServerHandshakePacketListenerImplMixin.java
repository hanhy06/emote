package io.github.hanhy06.emote.mixin;

import io.github.hanhy06.emote.Emote;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.handshake.ClientIntent;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.server.network.ServerHandshakePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerHandshakePacketListenerImpl.class)
public class ServerHandshakePacketListenerImplMixin {
	@Shadow
	@Final
	private Connection connection;

	@Inject(method = "handleIntention", at = @At("HEAD"))
	private void emote$rememberHost(ClientIntentionPacket packet, CallbackInfo callbackInfo) {
		if (packet.intention() != ClientIntent.LOGIN && packet.intention() != ClientIntent.TRANSFER) {
			return;
		}

		Emote.SKIN_MANAGER.rememberConnectionHost(this.connection, packet.hostName(), packet.port());
	}
}
