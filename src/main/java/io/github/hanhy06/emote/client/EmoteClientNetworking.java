package io.github.hanhy06.emote.client;

import io.github.hanhy06.emote.network.payload.EmotePlaybackStatePayload;
import io.github.hanhy06.emote.network.payload.EmoteSkinPortPayload;
import io.github.hanhy06.emote.network.payload.EmoteSkinSyncPayload;
import io.github.hanhy06.emote.network.payload.EmoteWheelSyncPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

@Environment(EnvType.CLIENT)
public class EmoteClientNetworking {
    private final PerspectiveController perspectiveController;
    private final ClientSkinOverrideController skinOverrideController;
    private final WheelController wheelController;

    public EmoteClientNetworking(
            PerspectiveController perspectiveController,
            ClientSkinOverrideController skinOverrideController,
            WheelController wheelController
    ) {
        this.perspectiveController = perspectiveController;
        this.skinOverrideController = skinOverrideController;
        this.wheelController = wheelController;
    }

    public void register() {
        registerPlaybackStateReceiver();
        registerSkinPortReceiver();
        registerSkinSyncReceiver();
        registerWheelSyncReceiver();
    }

    private void registerPlaybackStateReceiver() {
        ClientPlayNetworking.registerGlobalReceiver(EmotePlaybackStatePayload.TYPE, (payload, ignoredContext) ->
                Minecraft.getInstance().execute(() -> this.perspectiveController.handlePlaybackState(payload.active()))
        );
    }

    private void registerSkinPortReceiver() {
        ClientPlayNetworking.registerGlobalReceiver(EmoteSkinPortPayload.TYPE, (payload, ignoredContext) ->
                Minecraft.getInstance().execute(() -> this.skinOverrideController.updateServerPort(payload.port()))
        );
    }

    private void registerSkinSyncReceiver() {
        ClientPlayNetworking.registerGlobalReceiver(EmoteSkinSyncPayload.TYPE, (payload, ignoredContext) ->
                Minecraft.getInstance().execute(() -> this.skinOverrideController.updateEntries(payload.entries()))
        );
    }

    private void registerWheelSyncReceiver() {
        ClientPlayNetworking.registerGlobalReceiver(EmoteWheelSyncPayload.TYPE, (payload, ignoredContext) ->
                Minecraft.getInstance().execute(() -> this.wheelController.updateEmotes(payload.emotes()))
        );
    }
}
