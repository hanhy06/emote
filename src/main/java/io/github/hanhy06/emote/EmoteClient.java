package io.github.hanhy06.emote;

import io.github.hanhy06.emote.client.EmoteClientNetworking;
import io.github.hanhy06.emote.client.PerspectiveController;
import io.github.hanhy06.emote.client.WheelController;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public class EmoteClient implements ClientModInitializer {
    private static final PerspectiveController EMOTE_PERSPECTIVE_CONTROLLER = new PerspectiveController();
    private static final WheelController EMOTE_WHEEL_CONTROLLER = new WheelController();
    private static final KeyMapping EMOTE_WHEEL_KEY = KeyMappingHelper.registerKeyMapping(
        new KeyMapping("key.emote.wheel", GLFW.GLFW_KEY_V, KeyMapping.Category.MISC)
    );

    private final EmoteClientNetworking networking = new EmoteClientNetworking(
        EMOTE_PERSPECTIVE_CONTROLLER,
        EMOTE_WHEEL_CONTROLLER
    );

    @Override
    public void onInitializeClient() {
        this.networking.register();
        registerConnectionCallbacks();
        EMOTE_WHEEL_CONTROLLER.registerBinding(EMOTE_WHEEL_KEY);
    }

    public static boolean isPlaybackActive() {
        return EMOTE_PERSPECTIVE_CONTROLLER.isPlaybackActive();
    }

    private void registerConnectionCallbacks() {
        ClientPlayConnectionEvents.JOIN.register((ignoredHandler, ignoredSender, ignoredClient) -> clearClientState());
        ClientPlayConnectionEvents.DISCONNECT.register((ignoredHandler, ignoredClient) -> clearClientState());
    }

    private static void clearClientState() {
        EMOTE_PERSPECTIVE_CONTROLLER.clear();
        EMOTE_WHEEL_CONTROLLER.clear();
    }
}
