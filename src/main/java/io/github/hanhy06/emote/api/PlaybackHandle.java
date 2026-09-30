package io.github.hanhy06.emote.api;

import java.util.Optional;
import java.util.UUID;

public interface PlaybackHandle {
    UUID sessionId();

    PlaybackInfo info();

    PlaybackState state();

    Optional<PlaybackStopReason> stopReason();

    boolean pause();

    boolean resume();

    boolean finish();

    boolean stop();

    void onClose(Runnable cleanup);
}
