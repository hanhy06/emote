package io.github.hanhy06.emote.client;

import io.github.hanhy06.emote.network.payload.EmotePlaybackStatePayload;
import io.github.hanhy06.emote.network.payload.EmoteWheelSyncPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

@Environment(EnvType.CLIENT)
public class EmoteClientNetworking {
    public static EmoteClientNetworking INSTANCE;

    private final PerspectiveController perspectiveController;
    private final WheelController wheelController;

    public EmoteClientNetworking(
        PerspectiveController perspectiveController,
        WheelController wheelController
    ) {
        INSTANCE = this;
        this.perspectiveController = perspectiveController;
        this.wheelController = wheelController;
    }

    public void register() {
        ClientPlayNetworking.registerGlobalReceiver(EmotePlaybackStatePayload.TYPE, (payload, ignoredContext) ->
            Minecraft.getInstance().execute(() ->
                this.perspectiveController.handlePlaybackState(payload.active(), payload.hidePlayer())
            )
        );
        ClientPlayNetworking.registerGlobalReceiver(EmoteWheelSyncPayload.TYPE, (payload, ignoredContext) ->
            Minecraft.getInstance().execute(() -> this.wheelController.updateEmotes(payload.emotes()))
        );
    }
}
