package io.github.hanhy06.emote.network.payload;

import io.github.hanhy06.emote.Emote;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record EmoteSkinSupportPayload() implements CustomPacketPayload {
	public static final EmoteSkinSupportPayload INSTANCE = new EmoteSkinSupportPayload();
	public static final Type<EmoteSkinSupportPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Emote.MOD_ID, "skin_support"));
	public static final StreamCodec<RegistryFriendlyByteBuf, EmoteSkinSupportPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

	@Override
	public Type<EmoteSkinSupportPayload> type() {
		return TYPE;
	}
}
