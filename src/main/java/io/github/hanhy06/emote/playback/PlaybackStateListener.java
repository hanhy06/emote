package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.playback.session.PlaybackParticipant;
import io.github.hanhy06.emote.playback.session.PlaybackSession;
import net.minecraft.server.level.ServerPlayer;

public interface PlaybackStateListener {
    void onStarted(ServerPlayer player, PlaybackSession session, PlaybackParticipant participant);

    void onStopped(ServerPlayer player, PlaybackSession session, PlaybackParticipant participant, PlaybackStopReason reason);
}
