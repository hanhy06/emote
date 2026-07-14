package io.github.hanhy06.emote.client;

import io.github.hanhy06.emote.network.payload.EmotePlaybackStatePayload;
import io.github.hanhy06.emote.network.payload.EmoteWheelSyncPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

@Environment(EnvType.CLIENT)
public class EmoteClientNetworking {
    private final PerspectiveController perspectiveController;
    private final WheelController wheelController;

    public EmoteClientNetworking(
        PerspectiveController perspectiveController,
        WheelController wheelController
    ) {
        this.perspectiveController = perspectiveController;
        this.wheelController = wheelController;
    }

    public void register() {
        registerPlaybackStateReceiver();
        registerWheelSyncReceiver();
    }

    private void registerPlaybackStateReceiver() {
        ClientPlayNetworking.registerGlobalReceiver(EmotePlaybackStatePayload.TYPE, (payload, ignoredContext) ->
            Minecraft.getInstance().execute(() -> this.perspectiveController.handlePlaybackState(payload.active()))
        );
    }

    private void registerWheelSyncReceiver() {
        ClientPlayNetworking.registerGlobalReceiver(EmoteWheelSyncPayload.TYPE, (payload, ignoredContext) ->
            Minecraft.getInstance().execute(() -> this.wheelController.updateEmotes(payload.emotes()))
        );
    }
}
