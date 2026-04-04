package io.github.hanhy06.emote.network.service;

import io.github.hanhy06.emote.emote.EmoteAnimation;
import io.github.hanhy06.emote.emote.EmoteDefinition;
import io.github.hanhy06.emote.emote.PlayableEmoteSelection;
import io.github.hanhy06.emote.emote.PlayableEmoteSelectionResult;
import io.github.hanhy06.emote.emote.PlayableEmoteService;
import io.github.hanhy06.emote.playback.PlaybackManager;
import io.github.hanhy06.emote.playback.data.PlaybackStartResult;
import net.minecraft.server.level.ServerPlayer;

public class PlayService {
	private final PlayableEmoteService playableEmoteService;
	private final EmoteStarter emoteStarter;

	public PlayService(
		PlayableEmoteService playableEmoteService,
		PlaybackManager playbackManager
	) {
		this(playableEmoteService, playbackManager::startEmote);
	}

	PlayService(
		PlayableEmoteService playableEmoteService,
		EmoteStarter emoteStarter
	) {
		this.playableEmoteService = playableEmoteService;
		this.emoteStarter = emoteStarter;
	}

	public PlayResult playDefault(ServerPlayer player, String commandName) {
		return play(this.playableEmoteService.findDefaultSelection(player, commandName), player);
	}

	public PlayResult playSelection(ServerPlayer player, String commandName, String animationName) {
		return play(this.playableEmoteService.findSelection(player, commandName, animationName), player);
	}

	private PlayResult play(PlayableEmoteSelectionResult selectionResult, ServerPlayer player) {
		if (!selectionResult.isSuccess()) {
			return PlayResult.failure(selectionResult.errorMessage());
		}

		PlayableEmoteSelection selection = selectionResult.selection();
		PlaybackStartResult playResult = this.emoteStarter.start(player, selection.definition(), selection.animation());
		if (!playResult.isSuccess()) {
			return PlayResult.failure(playResult.errorMessage());
		}

		return PlayResult.success(selection.definition().createDisplayName(selection.animation().displayName()));
	}

	@FunctionalInterface
	interface EmoteStarter {
		PlaybackStartResult start(ServerPlayer player, EmoteDefinition definition, EmoteAnimation animation);
	}
}
