package io.github.hanhy06.emote.network.payload;

import io.github.hanhy06.emote.Emote;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record EmoteSkinPortPayload(int port) implements CustomPacketPayload {
	public static final Type<EmoteSkinPortPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Emote.MOD_ID, "skin_port"));
	public static final StreamCodec<RegistryFriendlyByteBuf, EmoteSkinPortPayload> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT,
			EmoteSkinPortPayload::port,
			EmoteSkinPortPayload::new
	);

	@Override
	public Type<EmoteSkinPortPayload> type() {
		return TYPE;
	}
}
