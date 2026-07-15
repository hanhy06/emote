package io.github.hanhy06.emote.emote;

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

    public PlayResult play(ServerPlayer player, String id) {
        PlayableEmoteSelection selection = this.playableEmoteService.findSelection(player, id);
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
