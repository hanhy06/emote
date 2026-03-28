package io.github.hanhy06.emote.network;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.emote.PlayableEmote;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

public record EmoteWheelSyncPayload(List<PlayableEmote> emotes) implements CustomPacketPayload {
	private static final StreamCodec<RegistryFriendlyByteBuf, PlayableEmote> PLAYABLE_EMOTE_STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.STRING_UTF8,
		PlayableEmote::commandName,
		ByteBufCodecs.STRING_UTF8,
		PlayableEmote::animationName,
		ByteBufCodecs.BOOL,
		PlayableEmote::defaultAnimation,
		ByteBufCodecs.STRING_UTF8,
		PlayableEmote::displayName,
		ByteBufCodecs.STRING_UTF8,
		PlayableEmote::description,
		PlayableEmote::new
	);

	public static final Type<EmoteWheelSyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Emote.MOD_ID, "wheel_sync"));
	public static final StreamCodec<RegistryFriendlyByteBuf, EmoteWheelSyncPayload> STREAM_CODEC = StreamCodec.composite(
		PLAYABLE_EMOTE_STREAM_CODEC.apply(ByteBufCodecs.list(512)),
		EmoteWheelSyncPayload::emotes,
		EmoteWheelSyncPayload::new
	);

	public EmoteWheelSyncPayload {
		emotes = List.copyOf(emotes);
	}

	@Override
	public Type<EmoteWheelSyncPayload> type() {
		return TYPE;
	}
}
