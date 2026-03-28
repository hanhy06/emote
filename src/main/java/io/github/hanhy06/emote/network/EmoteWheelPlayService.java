package io.github.hanhy06.emote.network;

import io.github.hanhy06.emote.emote.PlayableEmoteSelection;
import io.github.hanhy06.emote.emote.PlayableEmoteSelectionResult;
import io.github.hanhy06.emote.emote.PlayableEmoteService;
import io.github.hanhy06.emote.playback.EmotePlaybackManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class EmoteWheelPlayService {
	private final PlayableEmoteService playableEmoteService;
	private final EmotePlaybackManager emotePlaybackManager;

	public EmoteWheelPlayService(
		PlayableEmoteService playableEmoteService,
		EmotePlaybackManager emotePlaybackManager
	) {
		this.playableEmoteService = playableEmoteService;
		this.emotePlaybackManager = emotePlaybackManager;
	}

	public void playPlayerSelection(ServerPlayer player, EmoteWheelPlayPayload payload) {
		PlayableEmoteSelectionResult selectionResult = this.playableEmoteService.findSelection(
			player,
			payload.commandName(),
			payload.animationName()
		);
		if (!selectionResult.isSuccess()) {
			player.sendSystemMessage(Component.literal(selectionResult.errorMessage()));
			return;
		}

		PlayableEmoteSelection selection = selectionResult.selection();
		this.emotePlaybackManager.startEmote(player, selection.definition(), selection.animation())
			.ifPresent(errorMessage -> player.sendSystemMessage(Component.literal(errorMessage)));
	}
}
