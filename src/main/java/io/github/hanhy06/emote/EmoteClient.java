package io.github.hanhy06.emote;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.hanhy06.emote.client.EmoteClientNetworking;
import io.github.hanhy06.emote.client.PerspectiveController;
import io.github.hanhy06.emote.client.WheelController;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;

public class EmoteClient implements ClientModInitializer {
    private static final PerspectiveController EMOTE_PERSPECTIVE_CONTROLLER = new PerspectiveController();
    private static final WheelController EMOTE_WHEEL_CONTROLLER = new WheelController();
    private static final KeyMapping EMOTE_WHEEL_KEY = KeyMappingHelper.registerKeyMapping(
        new KeyMapping("key.emote.wheel", InputConstants.KEY_V, KeyMapping.Category.MISC)
    );

    private final EmoteClientNetworking networking = new EmoteClientNetworking(
        EMOTE_PERSPECTIVE_CONTROLLER,
        EMOTE_WHEEL_CONTROLLER
    );

    @Override
    public void onInitializeClient() {
        this.networking.register();
        ClientPlayConnectionEvents.JOIN.register((ignoredHandler, ignoredSender, ignoredClient) -> clearClientState());
        ClientPlayConnectionEvents.DISCONNECT.register((ignoredHandler, ignoredClient) -> clearClientState());
        EMOTE_WHEEL_CONTROLLER.registerBinding(EMOTE_WHEEL_KEY);
    }

    public static boolean shouldHideLocalPlayerEquipment() {
        return EMOTE_PERSPECTIVE_CONTROLLER.shouldHideLocalPlayerEquipment();
    }

    private static void clearClientState() {
        EMOTE_PERSPECTIVE_CONTROLLER.clear();
        EMOTE_WHEEL_CONTROLLER.clear();
    }
}
