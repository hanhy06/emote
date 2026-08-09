package io.github.hanhy06.emote.network;

import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.network.payload.EmotePlaybackStatePayload;
import io.github.hanhy06.emote.playback.ActiveEmote;
import io.github.hanhy06.emote.playback.PlaybackStateListener;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

public class PlaybackStateService implements PlaybackStateListener {
    public static PlaybackStateService INSTANCE;

    private static final EmotePlaybackStatePayload INACTIVE_PAYLOAD = new EmotePlaybackStatePayload(false, false);

    public PlaybackStateService() {
        INSTANCE = this;
    }

    @Override
    public void onEmoteStarted(ServerPlayer player, ActiveEmote activeEmote) {
        sync(player, new EmotePlaybackStatePayload(true, activeEmote.playerBehavior().hidden()));
    }

    @Override
    public void onEmoteStopped(ServerPlayer player, ActiveEmote activeEmote, PlaybackStopReason reason) {
        sync(player, INACTIVE_PAYLOAD);
    }

    private void sync(ServerPlayer player, EmotePlaybackStatePayload payload) {
        if (!ServerPlayNetworking.canSend(player, EmotePlaybackStatePayload.TYPE)) {
            return;
        }

        ServerPlayNetworking.send(player, payload);
    }
}
