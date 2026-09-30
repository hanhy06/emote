package io.github.hanhy06.emote.api;

import java.util.Optional;
import java.util.UUID;

public final class PlaybackHandleFixture implements PlaybackHandle {
    public static final PlayResult SUCCESS = new PlayResult.Success(new PlaybackHandleFixture());
    private final UUID sessionId = UUID.randomUUID();

    private PlaybackHandleFixture() {}

    public UUID sessionId() { return this.sessionId; }
    public PlaybackInfo info() { throw new UnsupportedOperationException(); }
    public PlaybackState state() { return PlaybackState.RUNNING; }
    public Optional<PlaybackStopReason> stopReason() { return Optional.empty(); }
    public boolean pause() { throw new UnsupportedOperationException(); }
    public boolean resume() { throw new UnsupportedOperationException(); }
    public boolean finish() { throw new UnsupportedOperationException(); }
    public boolean stop() { throw new UnsupportedOperationException(); }
    public void onClose(Runnable cleanup) { throw new UnsupportedOperationException(); }
}
