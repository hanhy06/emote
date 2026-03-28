package io.github.hanhy06.emote;

import io.github.hanhy06.emote.client.EmoteWheelController;
import io.github.hanhy06.emote.network.EmoteSkinSupportPayload;
import io.github.hanhy06.emote.network.EmoteWheelSyncPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public class EmoteClient implements ClientModInitializer {
	private static final EmoteWheelController EMOTE_WHEEL_CONTROLLER = new EmoteWheelController();
	private static final KeyMapping EMOTE_WHEEL_KEY = KeyMappingHelper.registerKeyMapping(
		new KeyMapping("key.emote.wheel", GLFW.GLFW_MOUSE_BUTTON_MIDDLE, KeyMapping.Category.MISC)
	);

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(EmoteWheelSyncPayload.TYPE, (payload, context) ->
			context.client().execute(() -> EMOTE_WHEEL_CONTROLLER.updateEmotes(payload.emotes()))
		);

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			EMOTE_WHEEL_CONTROLLER.clear();
			if (ClientPlayNetworking.canSend(EmoteSkinSupportPayload.TYPE)) {
				ClientPlayNetworking.send(EmoteSkinSupportPayload.INSTANCE);
			}
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> EMOTE_WHEEL_CONTROLLER.clear());

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.screen != null || client.player == null) {
				return;
			}

			while (EMOTE_WHEEL_KEY.consumeClick()) {
				drainPickItemClicks(client.options.keyPickItem);
				EMOTE_WHEEL_CONTROLLER.openWheel();
			}
		});
	}

	private static void drainPickItemClicks(KeyMapping keyMapping) {
		while (keyMapping.consumeClick()) {
		}
	}
}
