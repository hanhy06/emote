package io.github.hanhy06.emote.network.service;

import io.github.hanhy06.emote.emote.EmoteDefinition;
import io.github.hanhy06.emote.emote.PlayResult;
import io.github.hanhy06.emote.emote.PlayableEmoteSelection;
import io.github.hanhy06.emote.emote.PlayableEmoteService;
import io.github.hanhy06.emote.playback.PlaybackManager;
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

    public PlayResult play(ServerPlayer player, String commandName) {
        PlayableEmoteSelection selection = this.playableEmoteService.findSelection(player, commandName);
        if (!selection.isSuccess()) {
            return PlayResult.failure(selection.errorMessage());
        }
        return this.emoteStarter.start(player, selection.definition());
    }

    @FunctionalInterface
    interface EmoteStarter {
        PlayResult start(ServerPlayer player, EmoteDefinition definition);
    }
}
