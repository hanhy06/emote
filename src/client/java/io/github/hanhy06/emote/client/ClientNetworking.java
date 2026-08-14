package io.github.hanhy06.emote.client;

import io.github.hanhy06.emote.network.payload.PlaybackStatePayload;
import io.github.hanhy06.emote.network.payload.WheelSyncPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

@Environment(EnvType.CLIENT)
public class ClientNetworking {
    private final PerspectiveController perspectiveController;
    private final WheelController wheelController;

    public ClientNetworking(
        PerspectiveController perspectiveController,
        WheelController wheelController
    ) {
        this.perspectiveController = perspectiveController;
        this.wheelController = wheelController;
    }

    public void register() {
        ClientPlayNetworking.registerGlobalReceiver(PlaybackStatePayload.TYPE, (payload, ignoredContext) ->
            Minecraft.getInstance().execute(() ->
                this.perspectiveController.handlePlaybackState(payload.active(), payload.hidePlayer())
            )
        );
        ClientPlayNetworking.registerGlobalReceiver(WheelSyncPayload.TYPE, (payload, ignoredContext) ->
            Minecraft.getInstance().execute(() -> this.wheelController.updateEntries(payload.emotes()))
        );
    }
}
