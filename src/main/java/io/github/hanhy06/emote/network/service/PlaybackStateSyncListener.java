package io.github.hanhy06.emote.network.service;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.playback.PlaybackStateListener;
import io.github.hanhy06.emote.playback.data.ActiveEmote;
import net.minecraft.server.level.ServerPlayer;

public class PlaybackStateSyncListener implements PlaybackStateListener {
    private final PlaybackStateService playbackStateService;
    private final EmoteSkinSyncService emoteSkinSyncService;

    public PlaybackStateSyncListener(PlaybackStateService playbackStateService, EmoteSkinSyncService emoteSkinSyncService) {
        this.playbackStateService = playbackStateService;
        this.emoteSkinSyncService = emoteSkinSyncService;
    }

    @Override
    public void onEmoteStarted(ServerPlayer player, ActiveEmote activeEmote) {
        this.playbackStateService.syncActive(player);
        if (Emote.SERVER != null) {
            this.emoteSkinSyncService.syncAll(Emote.SERVER);
        }
    }

    @Override
    public void onEmoteStopped(ServerPlayer player, ActiveEmote activeEmote) {
        this.playbackStateService.syncInactive(player);
        if (Emote.SERVER != null) {
            this.emoteSkinSyncService.syncAll(Emote.SERVER);
        }
    }
}
