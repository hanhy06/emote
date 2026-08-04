package io.github.hanhy06.emote.network;

import io.github.hanhy06.emote.network.payload.EmotePlaybackStatePayload;
import io.github.hanhy06.emote.network.payload.EmoteWheelSyncPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public class EmoteNetworking {
    public static EmoteNetworking INSTANCE;

    public EmoteNetworking() {
        INSTANCE = this;
    }

    public void register() {
        PayloadTypeRegistry.clientboundPlay().register(EmotePlaybackStatePayload.TYPE, EmotePlaybackStatePayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(EmoteWheelSyncPayload.TYPE, EmoteWheelSyncPayload.STREAM_CODEC);
    }
}
