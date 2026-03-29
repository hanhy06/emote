package io.github.hanhy06.emote.network.payload;

import io.github.hanhy06.emote.Emote;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record EmotePlaybackStatePayload(boolean active) implements CustomPacketPayload {
	public static final Type<EmotePlaybackStatePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Emote.MOD_ID, "playback_state"));
	public static final StreamCodec<RegistryFriendlyByteBuf, EmotePlaybackStatePayload> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.BOOL,
		EmotePlaybackStatePayload::active,
		EmotePlaybackStatePayload::new
	);

	@Override
	public Type<EmotePlaybackStatePayload> type() {
		return TYPE;
	}
}
