package io.github.hanhy06.emote.network.service;

import io.github.hanhy06.emote.playback.PlaybackStateListener;
import io.github.hanhy06.emote.playback.data.ActiveEmote;
import net.minecraft.server.level.ServerPlayer;

public class PlaybackStateSyncListener implements PlaybackStateListener {
    private final PlaybackStateService playbackStateService;

    public PlaybackStateSyncListener(PlaybackStateService playbackStateService) {
        this.playbackStateService = playbackStateService;
    }

    @Override
    public void onEmoteStarted(ServerPlayer player, ActiveEmote activeEmote) {
        this.playbackStateService.syncActive(player);
    }

    @Override
    public void onEmoteStopped(ServerPlayer player, ActiveEmote activeEmote) {
        this.playbackStateService.syncInactive(player);
    }
}
