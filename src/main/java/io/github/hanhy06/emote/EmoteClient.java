package io.github.hanhy06.emote;

import io.github.hanhy06.emote.network.EmoteSkinSupportPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class EmoteClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			if (ClientPlayNetworking.canSend(EmoteSkinSupportPayload.TYPE)) {
				ClientPlayNetworking.send(EmoteSkinSupportPayload.INSTANCE);
			}
		});
	}
}
