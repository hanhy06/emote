package io.github.hanhy06.emote.mixin;

import io.github.hanhy06.emote.playback.stress.StressTestTiming;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
abstract class ConnectionMixin {
    @Inject(method = "configurePacketHandler", at = @At("TAIL"))
    private void emote$measureServerNetwork(ChannelPipeline pipeline, CallbackInfo callbackInfo) {
        if (((Connection) (Object) this).getReceiving() == PacketFlow.SERVERBOUND) {
            pipeline.addLast("emote_stress_timing", new StressTestTiming.NetworkHandler());
        }
    }
}
