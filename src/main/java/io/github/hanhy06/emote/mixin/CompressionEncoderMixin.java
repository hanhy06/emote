package io.github.hanhy06.emote.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.hanhy06.emote.network.NetworkBenchmark;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.CompressionEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(CompressionEncoder.class)
abstract class CompressionEncoderMixin {
    @Shadow public abstract int getThreshold();

    @WrapMethod(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lio/netty/buffer/ByteBuf;Lio/netty/buffer/ByteBuf;)V")
    private void emote$measureCompression(ChannelHandlerContext context, ByteBuf input, ByteBuf output, Operation<Void> original) {
        var frame = NetworkBenchmark.INSTANCE.encodedFrame(context.channel());
        if (frame == null) {
            original.call(context, input, output);
            return;
        }

        boolean deflated = input.readableBytes() >= getThreshold();
        long started = System.nanoTime();
        try {
            original.call(context, input, output);
        } catch (RuntimeException | Error failure) {
            frame.discard();
            throw failure;
        }
        long elapsed = System.nanoTime() - started;
        frame.compressed(output.readableBytes(), elapsed, deflated);
    }
}
