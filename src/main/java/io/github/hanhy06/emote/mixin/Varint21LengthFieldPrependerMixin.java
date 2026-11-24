package io.github.hanhy06.emote.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.hanhy06.emote.network.NetworkBenchmark;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Varint21LengthFieldPrepender;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Varint21LengthFieldPrepender.class)
abstract class Varint21LengthFieldPrependerMixin {
    @WrapMethod(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lio/netty/buffer/ByteBuf;Lio/netty/buffer/ByteBuf;)V")
    private void emote$measureFraming(ChannelHandlerContext context, ByteBuf input, ByteBuf output, Operation<Void> original) {
        var frame = NetworkBenchmark.INSTANCE.encodedFrame(context.channel());
        if (frame == null) {
            original.call(context, input, output);
            return;
        }

        long started = System.nanoTime();
        try {
            original.call(context, input, output);
        } catch (RuntimeException | Error failure) {
            frame.discard();
            throw failure;
        }
        long elapsed = System.nanoTime() - started;
        frame.framed(output.readableBytes(), elapsed);
    }
}
