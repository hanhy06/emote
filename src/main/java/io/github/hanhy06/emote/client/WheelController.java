package io.github.hanhy06.emote.client;

import io.github.hanhy06.emote.emote.PlayableEmote;
import io.github.hanhy06.emote.network.EmoteWheelPlayPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.util.List;

@Environment(EnvType.CLIENT)
public class WheelController {
	private static final String MENU_FALLBACK_COMMAND = "emote menu";
	private List<PlayableEmote> syncedEmotes = List.of();
	private boolean syncedFromServer;
	private String lastSelectionKey = "";

	public void clear() {
		this.syncedEmotes = List.of();
		this.syncedFromServer = false;
		this.lastSelectionKey = "";
	}

	public void updateEmotes(List<PlayableEmote> emotes) {
		this.syncedEmotes = List.copyOf(emotes);
		this.syncedFromServer = true;
	}

	public void openWheel(Component bindingLabel) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.screen != null) {
			return;
		}

		if (!this.syncedFromServer) {
			openMenuFallback(client.player);
			return;
		}

		client.setScreen(new WheelScreen(this, this.syncedEmotes, findInitialPageIndex(), bindingLabel));
	}

	public void playEmote(PlayableEmote playableEmote) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return;
		}

		this.lastSelectionKey = playableEmote.selectionKey();
		if (ClientPlayNetworking.canSend(EmoteWheelPlayPayload.TYPE)) {
			ClientPlayNetworking.send(new EmoteWheelPlayPayload(playableEmote.commandName(), playableEmote.animationName()));
			return;
		}

		player.connection.sendUnattendedCommand(playableEmote.createPlayCommand(), null);
	}

	private void openMenuFallback(LocalPlayer player) {
		player.connection.sendUnattendedCommand(MENU_FALLBACK_COMMAND, null);
	}

	private int findInitialPageIndex() {
		if (this.lastSelectionKey.isEmpty() || this.syncedEmotes.isEmpty()) {
			return 0;
		}

		for (int index = 0; index < this.syncedEmotes.size(); index++) {
			if (this.syncedEmotes.get(index).selectionKey().equals(this.lastSelectionKey)) {
				return index / WheelScreen.SLOT_COUNT;
			}
		}

		return 0;
	}
}
