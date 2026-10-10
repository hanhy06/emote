package io.github.hanhy06.emote.application;

import io.github.hanhy06.emote.api.PlayResult;
import io.github.hanhy06.emote.api.PlaySource;
import io.github.hanhy06.emote.api.PlaybackPlacement;
import io.github.hanhy06.emote.content.EmoteCatalog;
import io.github.hanhy06.emote.playback.PlayerPlaybackManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import io.github.hanhy06.emote.content.PreparedEmote;

public class EmotePlayService {
    private final EmoteCatalog emoteCatalog;
    private final PlaybackPolicyService playbackPolicy;
    private final PlayerPlaybackManager playerPlaybackManager;
    private final ApiEventDispatcher apiEvents;

    public EmotePlayService(
        EmoteCatalog emoteCatalog,
        PlaybackPolicyService playbackPolicy,
        PlayerPlaybackManager playerPlaybackManager,
        ApiEventDispatcher apiEvents
    ) {
        this.emoteCatalog = emoteCatalog;
        this.playbackPolicy = playbackPolicy;
        this.playerPlaybackManager = playerPlaybackManager;
        this.apiEvents = apiEvents;
    }

    public PlayResult play(ServerPlayer player, String id) {
        return play(player, id, PlaySource.COMMAND);
    }

    public PlayResult play(ServerPlayer player, String id, PlaySource source) {
        return play(player, id, source, PlaybackPlacement.actor());
    }

    public PlayResult play(ServerPlayer player, String id, PlaySource source, PlaybackPlacement placement) {
        PreparedEmote emote = this.emoteCatalog.find(id);
        if (emote == null) {
            return PlayResult.failure("That emote does not exist.");
        }
        PlaybackPolicyService.Decision decision = this.playbackPolicy.evaluate(player, emote, source);
        if (!decision.isAllowed()) {
            return decision.rejection();
        }
        Component cancellationMessage = this.apiEvents.beforePlay(player, emote, source);
        if (cancellationMessage != null) {
            return PlayResult.failure(cancellationMessage);
        }
        this.playbackPolicy.claimCooldown(decision);
        PlayResult result;
        try {
            result = this.playerPlaybackManager.start(player, emote, placement);
        } catch (RuntimeException | Error exception) {
            this.playbackPolicy.releaseCooldown(decision);
            throw exception;
        }
        if (!result.isSuccess()) {
            this.playbackPolicy.releaseCooldown(decision);
        }
        return result;
    }

}
