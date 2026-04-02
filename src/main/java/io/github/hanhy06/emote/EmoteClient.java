package io.github.hanhy06.emote;

import io.github.hanhy06.emote.client.EmoteClientNetworking;
import io.github.hanhy06.emote.client.ClientSkinOverrideController;
import io.github.hanhy06.emote.client.PerspectiveController;
import io.github.hanhy06.emote.client.WheelController;
import io.github.hanhy06.emote.network.payload.EmoteSkinSupportPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public class EmoteClient implements ClientModInitializer {
    public static final ClientSkinOverrideController SKIN_OVERRIDE_CONTROLLER = new ClientSkinOverrideController();
    private static final PerspectiveController EMOTE_PERSPECTIVE_CONTROLLER = new PerspectiveController();
    private static final WheelController EMOTE_WHEEL_CONTROLLER = new WheelController();
    private static final KeyMapping EMOTE_WHEEL_KEY = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.emote.wheel", GLFW.GLFW_KEY_V, KeyMapping.Category.MISC)
    );

    private final EmoteClientNetworking networking = new EmoteClientNetworking(
            EMOTE_PERSPECTIVE_CONTROLLER,
            SKIN_OVERRIDE_CONTROLLER,
            EMOTE_WHEEL_CONTROLLER
    );

    @Override
    public void onInitializeClient() {
        this.networking.register();
        registerConnectionCallbacks();
        EMOTE_WHEEL_CONTROLLER.registerBinding(EMOTE_WHEEL_KEY);
    }

    private void registerConnectionCallbacks() {
        ClientPlayConnectionEvents.JOIN.register((ignoredHandler, ignoredSender, client) -> handleJoin(client));
        ClientPlayConnectionEvents.DISCONNECT.register((ignoredHandler, ignoredClient) -> clearClientState());
    }

    private void handleJoin(net.minecraft.client.Minecraft client) {
        clearClientState();
        SKIN_OVERRIDE_CONTROLLER.rememberServerHost(client);
        sendSkinSupportIfAvailable();
    }

    private void sendSkinSupportIfAvailable() {
        if (!ClientPlayNetworking.canSend(EmoteSkinSupportPayload.TYPE)) {
            return;
        }

        ClientPlayNetworking.send(EmoteSkinSupportPayload.INSTANCE);
    }

    private static void clearClientState() {
        SKIN_OVERRIDE_CONTROLLER.clear();
        EMOTE_PERSPECTIVE_CONTROLLER.clear();
        EMOTE_WHEEL_CONTROLLER.clear();
    }
}
