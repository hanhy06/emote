package io.github.hanhy06.emote.network.payload;

import io.github.hanhy06.emote.Emote;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record EmoteWheelPlayPayload(String commandName, String animationName) implements CustomPacketPayload {
	public static final Type<EmoteWheelPlayPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Emote.MOD_ID, "wheel_play"));
	public static final StreamCodec<RegistryFriendlyByteBuf, EmoteWheelPlayPayload> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.STRING_UTF8,
		EmoteWheelPlayPayload::commandName,
		ByteBufCodecs.STRING_UTF8,
		EmoteWheelPlayPayload::animationName,
		EmoteWheelPlayPayload::new
	);

	@Override
	public Type<EmoteWheelPlayPayload> type() {
		return TYPE;
	}
}
