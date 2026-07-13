package io.github.hanhy06.emote.network;

import io.github.hanhy06.emote.network.payload.EmotePlaybackStatePayload;
import io.github.hanhy06.emote.network.payload.EmoteWheelPlayPayload;
import io.github.hanhy06.emote.network.payload.EmoteWheelSyncPayload;
import io.github.hanhy06.emote.network.service.PlayService;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

public class EmoteNetworking {
    private final PlayService playService;

    public EmoteNetworking(PlayService playService) {
        this.playService = playService;
    }

    public void register() {
        registerPayloadTypes();
        registerReceivers();
    }

    private void registerPayloadTypes() {
        PayloadTypeRegistry.serverboundPlay().register(EmoteWheelPlayPayload.TYPE, EmoteWheelPlayPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(EmotePlaybackStatePayload.TYPE, EmotePlaybackStatePayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(EmoteWheelSyncPayload.TYPE, EmoteWheelSyncPayload.STREAM_CODEC);
    }

    private void registerReceivers() {
        registerWheelPlayReceiver();
    }

    private void registerWheelPlayReceiver() {
        ServerPlayNetworking.registerGlobalReceiver(EmoteWheelPlayPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> play(player, payload));
        });
    }

    private void play(ServerPlayer player, EmoteWheelPlayPayload payload) {
        this.playService.play(player, payload.commandName());
    }
}
