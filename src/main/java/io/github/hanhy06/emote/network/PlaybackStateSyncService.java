package io.github.hanhy06.emote.network;

import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.network.payload.PlaybackStatePayload;
import io.github.hanhy06.emote.playback.PlaybackStateListener;
import io.github.hanhy06.emote.playback.session.PlaybackParticipant;
import io.github.hanhy06.emote.playback.session.PlaybackSession;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

public class PlaybackStateSyncService implements PlaybackStateListener {
    private static final PlaybackStatePayload INACTIVE_PAYLOAD = new PlaybackStatePayload(false, false);

    @Override
    public void onStarted(ServerPlayer player, PlaybackSession session, PlaybackParticipant participant) {
        sync(player, new PlaybackStatePayload(true, session.playerBehavior().hidden()));
    }

    @Override
    public void onStopped(ServerPlayer player, PlaybackSession session, PlaybackParticipant participant, PlaybackStopReason reason) {
        sync(player, INACTIVE_PAYLOAD);
    }

    private void sync(ServerPlayer player, PlaybackStatePayload payload) {
        if (!ServerPlayNetworking.canSend(player, PlaybackStatePayload.TYPE)) {
            return;
        }

        ServerPlayNetworking.send(player, payload);
    }
}
