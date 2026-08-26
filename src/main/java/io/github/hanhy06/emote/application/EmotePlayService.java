package io.github.hanhy06.emote.application;

import io.github.hanhy06.emote.api.PlayResult;
import io.github.hanhy06.emote.api.PlaySource;
import io.github.hanhy06.emote.content.EmoteCatalog;
import io.github.hanhy06.emote.content.PlayableEmote;
import io.github.hanhy06.emote.playback.PlaybackEngine;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class EmotePlayService {
    private final EmoteCatalog emoteCatalog;
    private final PlaybackPolicyService playbackPolicy;
    private final PlaybackStarter emoteStarter;
    private final PlayEventDispatcher eventDispatcher;

    public EmotePlayService(
        EmoteCatalog emoteCatalog,
        PlaybackPolicyService playbackPolicy,
        PlaybackEngine playbackEngine,
        ApiEventDispatcher apiEvents
    ) {
        this(
            emoteCatalog,
            playbackPolicy,
            playbackEngine::start,
            apiEvents::beforePlay
        );
    }

    EmotePlayService(
        EmoteCatalog emoteCatalog,
        PlaybackPolicyService playbackPolicy,
        PlaybackStarter emoteStarter,
        PlayEventDispatcher eventDispatcher
    ) {
        this.emoteCatalog = emoteCatalog;
        this.playbackPolicy = playbackPolicy;
        this.emoteStarter = emoteStarter;
        this.eventDispatcher = eventDispatcher;
    }

    public PlayResult play(ServerPlayer player, String id) {
        return play(player, id, PlaySource.COMMAND);
    }

    public PlayResult play(ServerPlayer player, String id, PlaySource source) {
        PlayableEmote emote = this.emoteCatalog.find(id);
        if (emote == null) {
            return PlayResult.failure("That emote does not exist.");
        }
        PlaybackPolicyService.Decision decision = this.playbackPolicy.evaluate(player, emote, source);
        if (!decision.isAllowed()) {
            return decision.rejection();
        }
        Component cancellationMessage = this.eventDispatcher.beforePlay(player, emote, source);
        if (cancellationMessage != null) {
            return PlayResult.failure(cancellationMessage);
        }
        PlayResult result = this.emoteStarter.start(player, emote);
        if (result.isSuccess()) {
            this.playbackPolicy.onPlaybackStarted(decision);
        }
        return result;
    }

    @FunctionalInterface
    interface PlaybackStarter {
        PlayResult start(ServerPlayer player, PlayableEmote emote);
    }

    @FunctionalInterface
    interface PlayEventDispatcher {
        Component beforePlay(ServerPlayer player, PlayableEmote emote, PlaySource source);
    }
}
