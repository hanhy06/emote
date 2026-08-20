package io.github.hanhy06.emote.network.payload;

import io.github.hanhy06.emote.EmoteMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

public record PlaybackStatePayload(boolean active, boolean hidePlayer) implements CustomPacketPayload {
    public static final Type<PlaybackStatePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(EmoteMod.MOD_ID, "playback_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PlaybackStatePayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.BOOL,
        PlaybackStatePayload::active,
        ByteBufCodecs.BOOL,
        PlaybackStatePayload::hidePlayer,
        PlaybackStatePayload::new
    );

    @Override
    public @NonNull Type<PlaybackStatePayload> type() {
        return TYPE;
    }
}
