package io.github.hanhy06.emote.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.hanhy06.emote.network.VirtualDisplaySender;
import io.github.hanhy06.emote.network.NetworkBenchmark;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import net.minecraft.network.BandwidthDebugMonitor;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
abstract class ConnectionMixin implements VirtualDisplaySender.SocketConnection {
    @Shadow private Channel channel;
    @Shadow private int sentPackets;
    @Unique private volatile int emote$compressionThreshold = -1;
    @Unique private volatile int emote$displayEpoch;

    @WrapMethod(method = "setupCompression")
    private void emote$rememberCompression(int threshold, boolean validate, Operation<Void> original) {
        original.call(threshold, validate);
        this.emote$compressionThreshold = Math.max(-1, threshold);
    }

    @Override public Channel emote$channel() { return this.channel; }
    @Override public int emote$compressionThreshold() { return this.emote$compressionThreshold; }
    @Override public int emote$displayEpoch() { return this.emote$displayEpoch; }
    @Override public void emote$invalidateDisplayPackets() { this.emote$displayEpoch++; }
    @Override public void emote$countDisplayPackets(int count) { this.sentPackets += count; }

    @Inject(method = "configureSerialization", at = @At("RETURN"))
    private static void emote$installNetworkProbe(ChannelPipeline pipeline, PacketFlow inboundDirection, boolean local,
                                                   @Nullable BandwidthDebugMonitor monitor, CallbackInfo info) {
        if (inboundDirection == PacketFlow.SERVERBOUND && !local && pipeline.channel() instanceof SocketChannel) {
            NetworkBenchmark.INSTANCE.installSocketProbe(pipeline);
        }
    }
}
