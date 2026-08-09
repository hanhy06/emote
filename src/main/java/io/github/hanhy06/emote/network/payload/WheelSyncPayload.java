package io.github.hanhy06.emote.network.payload;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.emote.EmoteRegistry;
import io.github.hanhy06.emote.emote.PlayableEmote;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

import java.util.List;

public record WheelSyncPayload(List<PlayableEmote> emotes) implements CustomPacketPayload {
    private static final StreamCodec<RegistryFriendlyByteBuf, PlayableEmote> PLAYABLE_EMOTE_STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8,
        PlayableEmote::id,
        ByteBufCodecs.STRING_UTF8,
        PlayableEmote::displayName,
        ByteBufCodecs.STRING_UTF8,
        PlayableEmote::description,
        PlayableEmote::new
    );

    public static final Type<WheelSyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Emote.MOD_ID, "wheel_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WheelSyncPayload> STREAM_CODEC = StreamCodec.composite(
        PLAYABLE_EMOTE_STREAM_CODEC.apply(ByteBufCodecs.list(EmoteRegistry.MAX_EMOTE_COUNT)),
        WheelSyncPayload::emotes,
        WheelSyncPayload::new
    );

    public WheelSyncPayload {
        emotes = List.copyOf(emotes);
    }

    @Override
    public @NonNull Type<WheelSyncPayload> type() {
        return TYPE;
    }
}
