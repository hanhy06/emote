package io.github.hanhy06.emote.network.payload;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.playback.data.BoundEmoteSkinPart;
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
	private static final StreamCodec<RegistryFriendlyByteBuf, BoundEmoteSkinPart> EMOTE_SKIN_PART_STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT,
			BoundEmoteSkinPart::entityId,
			ByteBufCodecs.VAR_INT,
			part -> part.skinPart().partIndex(),
			ByteBufCodecs.STRING_UTF8,
			part -> part.skinPart().skinPart().id(),
			ByteBufCodecs.VAR_INT,
			part -> part.skinPart().skinSegment().startY(),
			ByteBufCodecs.VAR_INT,
			part -> part.skinPart().skinSegment().endY(),
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
			List<BoundEmoteSkinPart> skinParts
	) {
		public Entry {
			Objects.requireNonNull(namespace, "namespace");
			Objects.requireNonNull(textureHash, "textureHash");
			skinParts = List.copyOf(skinParts);
		}
	}

	private static BoundEmoteSkinPart createSkinPart(int entityId, int partIndex, String skinPartId, int startY, int endY) {
		PlayerSkinPart skinPart = PlayerSkinPart.fromId(skinPartId);
		if (skinPart == null) {
			throw new IllegalArgumentException("unknown skinPartId: " + skinPartId);
		}

		return new BoundEmoteSkinPart(entityId, new EmoteSkinPart(partIndex, skinPart, new PlayerSkinSegment(startY, endY)));
	}
}
