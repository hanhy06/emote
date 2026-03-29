package io.github.hanhy06.emote;

import io.github.hanhy06.emote.client.PerspectiveController;
import io.github.hanhy06.emote.client.WheelController;
import io.github.hanhy06.emote.client.WheelScreen;
import io.github.hanhy06.emote.network.payload.EmotePlaybackStatePayload;
import io.github.hanhy06.emote.network.payload.EmoteSkinSupportPayload;
import io.github.hanhy06.emote.network.payload.EmoteWheelSyncPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public class EmoteClient implements ClientModInitializer {
	private static final PerspectiveController EMOTE_PERSPECTIVE_CONTROLLER = new PerspectiveController();
	private static final WheelController EMOTE_WHEEL_CONTROLLER = new WheelController();
	private static final KeyMapping EMOTE_WHEEL_KEY = KeyMappingHelper.registerKeyMapping(
		new KeyMapping("key.emote.wheel", GLFW.GLFW_KEY_V, KeyMapping.Category.MISC)
	);
	private static boolean wheelBindingReleaseArmed;
	private static boolean wheelHoldWasDown;

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(EmotePlaybackStatePayload.TYPE, (payload, context) ->
			context.client().execute(() -> EMOTE_PERSPECTIVE_CONTROLLER.handlePlaybackState(payload.active()))
		);

		ClientPlayNetworking.registerGlobalReceiver(EmoteWheelSyncPayload.TYPE, (payload, context) ->
			context.client().execute(() -> EMOTE_WHEEL_CONTROLLER.updateEmotes(payload.emotes()))
		);

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			clearClientState();
			if (ClientPlayNetworking.canSend(EmoteSkinSupportPayload.TYPE)) {
				ClientPlayNetworking.send(EmoteSkinSupportPayload.INSTANCE);
			}
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clearClientState());

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			boolean wheelHoldDown = isEmoteBindingDown(client);

			if (client.screen instanceof WheelScreen wheelScreen) {
				if (wheelHoldDown) {
					wheelBindingReleaseArmed = true;
				} else if (wheelBindingReleaseArmed) {
					wheelBindingReleaseArmed = false;
					wheelScreen.handleBindingReleased();
				}
				wheelHoldWasDown = wheelHoldDown;
				return;
			}

			wheelBindingReleaseArmed = false;
			if (client.screen != null || client.player == null) {
				wheelHoldWasDown = wheelHoldDown;
				return;
			}

			if (wheelHoldDown && !wheelHoldWasDown) {
				if (EMOTE_WHEEL_KEY.same(client.options.keyPickItem)) {
					drainPickItemClicks(client.options.keyPickItem);
				}
				EMOTE_WHEEL_CONTROLLER.openWheel(EMOTE_WHEEL_KEY.getTranslatedKeyMessage());
				wheelBindingReleaseArmed = true;
			}

			wheelHoldWasDown = wheelHoldDown;
		});
	}

	private static void drainPickItemClicks(KeyMapping keyMapping) {
		while (keyMapping.consumeClick()) {
		}
	}

	private static void clearClientState() {
		EMOTE_PERSPECTIVE_CONTROLLER.clear();
		EMOTE_WHEEL_CONTROLLER.clear();
		wheelBindingReleaseArmed = false;
		wheelHoldWasDown = false;
	}

	private static boolean isEmoteBindingDown(net.minecraft.client.Minecraft client) {
		InputConstants.Key boundKey = KeyMappingHelper.getBoundKeyOf(EMOTE_WHEEL_KEY);
		return isBoundKeyDown(client, boundKey);
	}

	private static boolean isMouseButtonDown(net.minecraft.client.Minecraft client, int button) {
		return GLFW.glfwGetMouseButton(client.getWindow().handle(), button) == GLFW.GLFW_PRESS;
	}

	private static boolean isBoundKeyDown(net.minecraft.client.Minecraft client, InputConstants.Key key) {
		return switch (key.getType()) {
			case KEYSYM -> InputConstants.isKeyDown(client.getWindow(), key.getValue());
			case MOUSE -> isMouseButtonDown(client, key.getValue());
			case SCANCODE -> EMOTE_WHEEL_KEY.isDown();
		};
	}
}
