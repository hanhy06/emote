package io.github.hanhy06.emote.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.hanhy06.emote.network.VirtualDisplaySender;
import io.github.hanhy06.emote.network.NetworkBenchmark;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(PacketEncoder.class)
abstract class PacketEncoderMixin implements VirtualDisplaySender.EncoderProtocol {
    @Shadow @Final private ProtocolInfo<?> protocolInfo;

    @Override
    public ConnectionProtocol emote$protocol() {
        return this.protocolInfo.id();
    }

    @WrapMethod(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V")
    private void emote$measureEncoding(ChannelHandlerContext context, Packet<?> packet, ByteBuf output, Operation<Void> original) {
        var frame = NetworkBenchmark.INSTANCE.beginFrame(context.channel());
        if (frame == null) {
            original.call(context, packet, output);
            return;
        }

        long started = System.nanoTime();
        original.call(context, packet, output);
        long elapsed = System.nanoTime() - started;
        frame.encoded(packet, output.readableBytes(), elapsed);
    }
}
