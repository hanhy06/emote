package io.github.hanhy06.emote.network.payload;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.skin.EmoteSkinPart;
import io.github.hanhy06.emote.skin.PlayerSkinPart;
import io.github.hanhy06.emote.skin.PlayerSkinSegment;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Objects;

public record EmoteSkinSyncPayload(List<Entry> entries) implements CustomPacketPayload {
	private static final StreamCodec<RegistryFriendlyByteBuf, EmoteSkinPart> EMOTE_SKIN_PART_STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT,
			EmoteSkinPart::partIndex,
			ByteBufCodecs.STRING_UTF8,
			part -> part.skinPart().id(),
			ByteBufCodecs.VAR_INT,
			part -> part.skinSegment().startY(),
			ByteBufCodecs.VAR_INT,
			part -> part.skinSegment().endY(),
			EmoteSkinSyncPayload::createSkinPart
	);
	private static final StreamCodec<RegistryFriendlyByteBuf, Entry> ENTRY_STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8,
			Entry::namespace,
			ByteBufCodecs.STRING_UTF8,
			Entry::textureHash,
			ByteBufCodecs.BOOL,
			Entry::slimModel,
			EMOTE_SKIN_PART_STREAM_CODEC.apply(ByteBufCodecs.list(512)),
			Entry::skinParts,
			Entry::new
	);

	public static final Type<EmoteSkinSyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Emote.MOD_ID, "skin_sync"));
	public static final StreamCodec<RegistryFriendlyByteBuf, EmoteSkinSyncPayload> STREAM_CODEC = StreamCodec.composite(
			ENTRY_STREAM_CODEC.apply(ByteBufCodecs.list(512)),
			EmoteSkinSyncPayload::entries,
			EmoteSkinSyncPayload::new
	);

	public EmoteSkinSyncPayload {
		entries = List.copyOf(entries);
	}

	@Override
	public Type<EmoteSkinSyncPayload> type() {
		return TYPE;
	}

	public record Entry(
			String namespace,
			String textureHash,
			boolean slimModel,
			List<EmoteSkinPart> skinParts
	) {
		public Entry {
			Objects.requireNonNull(namespace, "namespace");
			Objects.requireNonNull(textureHash, "textureHash");
			skinParts = List.copyOf(skinParts);
		}
	}

	private static EmoteSkinPart createSkinPart(int partIndex, String skinPartId, int startY, int endY) {
		PlayerSkinPart skinPart = PlayerSkinPart.fromId(skinPartId);
		if (skinPart == null) {
			throw new IllegalArgumentException("unknown skinPartId: " + skinPartId);
		}

		return new EmoteSkinPart(partIndex, skinPart, new PlayerSkinSegment(startY, endY));
	}
}
