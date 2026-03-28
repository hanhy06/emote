package io.github.hanhy06.emote;

import io.github.hanhy06.emote.client.EmotePerspectiveController;
import io.github.hanhy06.emote.client.EmoteWheelController;
import io.github.hanhy06.emote.client.EmoteWheelScreen;
import io.github.hanhy06.emote.network.EmotePlaybackStatePayload;
import io.github.hanhy06.emote.network.EmoteSkinSupportPayload;
import io.github.hanhy06.emote.network.EmoteWheelSyncPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class EmoteClient implements ClientModInitializer {
	private static final EmotePerspectiveController EMOTE_PERSPECTIVE_CONTROLLER = new EmotePerspectiveController();
	private static final EmoteWheelController EMOTE_WHEEL_CONTROLLER = new EmoteWheelController();
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
			EMOTE_PERSPECTIVE_CONTROLLER.clear();
			EMOTE_WHEEL_CONTROLLER.clear();
			if (ClientPlayNetworking.canSend(EmoteSkinSupportPayload.TYPE)) {
				ClientPlayNetworking.send(EmoteSkinSupportPayload.INSTANCE);
			}
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			EMOTE_PERSPECTIVE_CONTROLLER.clear();
			EMOTE_WHEEL_CONTROLLER.clear();
			wheelBindingReleaseArmed = false;
			wheelHoldWasDown = false;
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			boolean middleMouseDown = isMouseButtonDown(client, GLFW.GLFW_MOUSE_BUTTON_MIDDLE);
			boolean emoteBindingDown = isEmoteBindingDown(client);
			boolean wheelHoldDown = middleMouseDown || emoteBindingDown;

			if (client.screen instanceof EmoteWheelScreen emoteWheelScreen) {
				if (wheelHoldDown) {
					wheelBindingReleaseArmed = true;
				} else if (wheelBindingReleaseArmed) {
					wheelBindingReleaseArmed = false;
					emoteWheelScreen.handleBindingReleased();
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
				if (middleMouseDown || EMOTE_WHEEL_KEY.same(client.options.keyPickItem)) {
					drainPickItemClicks(client.options.keyPickItem);
				}
				EMOTE_WHEEL_CONTROLLER.openWheel(findBindingLabel(middleMouseDown));
				wheelBindingReleaseArmed = true;
			}

			wheelHoldWasDown = wheelHoldDown;
		});
	}

	private static void drainPickItemClicks(KeyMapping keyMapping) {
		while (keyMapping.consumeClick()) {
		}
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

	private static Component findBindingLabel(boolean middleMouseDown) {
		return middleMouseDown
			? Component.translatable("key.mouse.middle")
			: EMOTE_WHEEL_KEY.getTranslatedKeyMessage();
	}
}
