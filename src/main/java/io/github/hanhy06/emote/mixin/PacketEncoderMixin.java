package io.github.hanhy06.emote.mixin;

import io.github.hanhy06.emote.network.VirtualDisplaySender;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.ProtocolInfo;
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

}
