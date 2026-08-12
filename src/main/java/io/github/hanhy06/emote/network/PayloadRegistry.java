package io.github.hanhy06.emote.network;

import io.github.hanhy06.emote.network.payload.PlaybackStatePayload;
import io.github.hanhy06.emote.network.payload.WheelSyncPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public final class PayloadRegistry {
    private PayloadRegistry() {
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(PlaybackStatePayload.TYPE, PlaybackStatePayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(WheelSyncPayload.TYPE, WheelSyncPayload.STREAM_CODEC);
    }
}
