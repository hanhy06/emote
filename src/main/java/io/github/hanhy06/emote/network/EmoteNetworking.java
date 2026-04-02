package io.github.hanhy06.emote.network;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.network.payload.EmotePlaybackStatePayload;
import io.github.hanhy06.emote.network.payload.EmoteSkinPortPayload;
import io.github.hanhy06.emote.network.payload.EmoteSkinSupportPayload;
import io.github.hanhy06.emote.network.payload.EmoteSkinSyncPayload;
import io.github.hanhy06.emote.network.payload.EmoteWheelPlayPayload;
import io.github.hanhy06.emote.network.payload.EmoteWheelSyncPayload;
import io.github.hanhy06.emote.network.service.EmoteSkinSyncService;
import io.github.hanhy06.emote.network.service.PlayService;
import io.github.hanhy06.emote.network.service.WheelSyncService;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public class EmoteNetworking {
    private final PlayService playService;
    private final WheelSyncService wheelSyncService;
    private final EmoteSkinSyncService emoteSkinSyncService;

    public EmoteNetworking(
            PlayService playService,
            WheelSyncService wheelSyncService,
            EmoteSkinSyncService emoteSkinSyncService
    ) {
        this.playService = playService;
        this.wheelSyncService = wheelSyncService;
        this.emoteSkinSyncService = emoteSkinSyncService;
    }

    public void register() {
        registerPayloadTypes();
        registerReceivers();
    }

    private void registerPayloadTypes() {
        PayloadTypeRegistry.serverboundPlay().register(EmoteSkinSupportPayload.TYPE, EmoteSkinSupportPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(EmoteWheelPlayPayload.TYPE, EmoteWheelPlayPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(EmotePlaybackStatePayload.TYPE, EmotePlaybackStatePayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(EmoteSkinPortPayload.TYPE, EmoteSkinPortPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(EmoteSkinSyncPayload.TYPE, EmoteSkinSyncPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(EmoteWheelSyncPayload.TYPE, EmoteWheelSyncPayload.STREAM_CODEC);
    }

    private void registerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(EmoteSkinSupportPayload.TYPE, (payload, context) ->
                context.server().execute(() -> {
                    int resolvedPort = Emote.SKIN_MANAGER.getResolvedPort();
                    ServerPlayNetworking.send(context.player(), new EmoteSkinPortPayload(resolvedPort));
                    this.emoteSkinSyncService.syncPlayer(context.player());
                    this.wheelSyncService.syncPlayer(context.player());
                })
        );
        ServerPlayNetworking.registerGlobalReceiver(EmoteWheelPlayPayload.TYPE, (payload, context) ->
                context.server().execute(() -> this.playService.playSelection(
                        context.player(),
                        payload.commandName(),
                        payload.animationName()
                ))
        );
    }
}
