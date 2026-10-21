package io.github.hanhy06.emote;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.hanhy06.emote.client.PerspectiveController;
import io.github.hanhy06.emote.client.WheelController;
import io.github.hanhy06.emote.client.WheelShortcutSettings;
import io.github.hanhy06.emote.network.payload.PlaybackStatePayload;
import io.github.hanhy06.emote.network.payload.WheelSyncPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

public class EmoteClientMod implements ClientModInitializer {
    private static final KeyMapping EMOTE_WHEEL_KEY = KeyMappingHelper.registerKeyMapping(
        new KeyMapping("key.emote.wheel", InputConstants.KEY_V, KeyMapping.Category.MISC)
    );

    @Override
    public void onInitializeClient() {
        PerspectiveController perspectiveController = new PerspectiveController();
        WheelShortcutSettings wheelShortcutSettings = new WheelShortcutSettings(
            FabricLoader.getInstance().getConfigDir().resolve(EmoteMod.MOD_ID).resolve("wheel-shortcuts.json")
        );
        WheelController wheelController = new WheelController(wheelShortcutSettings);

        ClientPlayNetworking.registerGlobalReceiver(PlaybackStatePayload.TYPE, (payload, ignoredContext) ->
            Minecraft.getInstance().execute(() -> perspectiveController.handlePlaybackState(payload.active(), payload.hidePlayer()))
        );
        ClientPlayNetworking.registerGlobalReceiver(WheelSyncPayload.TYPE, (payload, ignoredContext) ->
            Minecraft.getInstance().execute(() -> wheelController.updateEntries(payload.emotes()))
        );
        ClientPlayConnectionEvents.JOIN.register(
            (ignoredHandler, ignoredSender, ignoredClient) -> clearClientState(perspectiveController, wheelController)
        );
        ClientPlayConnectionEvents.DISCONNECT.register(
            (ignoredHandler, ignoredClient) -> clearClientState(perspectiveController, wheelController)
        );
        wheelController.registerBinding(EMOTE_WHEEL_KEY);
    }

    private static void clearClientState(
        PerspectiveController perspectiveController,
        WheelController wheelController
    ) {
        perspectiveController.clear();
        wheelController.clear();
    }
}
