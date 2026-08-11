package io.github.hanhy06.emote;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.hanhy06.emote.client.ClientNetworking;
import io.github.hanhy06.emote.client.PerspectiveController;
import io.github.hanhy06.emote.client.WheelController;
import io.github.hanhy06.emote.client.WheelShortcutSettings;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;

public class EmoteClient implements ClientModInitializer {
    private static final KeyMapping EMOTE_WHEEL_KEY = KeyMappingHelper.registerKeyMapping(
        new KeyMapping("key.emote.wheel", InputConstants.KEY_V, KeyMapping.Category.MISC)
    );

    @Override
    public void onInitializeClient() {
        PerspectiveController perspectiveController = new PerspectiveController();
        WheelShortcutSettings wheelShortcutSettings = new WheelShortcutSettings(
            FabricLoader.getInstance().getConfigDir().resolve(Emote.MOD_ID).resolve("wheel-shortcuts.json")
        );
        WheelController wheelController = new WheelController(wheelShortcutSettings);

        ClientNetworking clientNetworking = new ClientNetworking(perspectiveController, wheelController);

        clientNetworking.register();
        ClientPlayConnectionEvents.JOIN.register((ignoredHandler, ignoredSender, ignoredClient) -> clearClientState());
        ClientPlayConnectionEvents.DISCONNECT.register((ignoredHandler, ignoredClient) -> clearClientState());
        wheelController.registerBinding(EMOTE_WHEEL_KEY);
    }

    public static boolean shouldHideLocalPlayerEquipment() {
        return PerspectiveController.INSTANCE.shouldHideLocalPlayerEquipment();
    }

    private static void clearClientState() {
        PerspectiveController.INSTANCE.clear();
        WheelController.INSTANCE.clear();
    }
}
