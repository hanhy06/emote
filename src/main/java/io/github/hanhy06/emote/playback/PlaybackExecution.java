package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.PlaybackHandle;
import io.github.hanhy06.emote.api.PlaybackInfo;
import io.github.hanhy06.emote.api.PlaybackState;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.playback.session.PlaybackSession;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class PlaybackExecution implements PlaybackHandle {
    private final PlaybackSession session;
    private final ArrayDeque<Runnable> cleanup = new ArrayDeque<>();
    private @Nullable Control control;
    private PlaybackState state = PlaybackState.RUNNING;
    private @Nullable PlaybackStopReason stopReason;
    private long elapsedTicks;
    private long lastServerTick = Long.MIN_VALUE;

    public PlaybackExecution(PlaybackSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    public void bind(Control control, long serverTick) {
        if (this.control != null) throw new IllegalStateException("Playback control is already bound.");
        this.control = Objects.requireNonNull(control, "control");
        this.lastServerTick = serverTick;
    }

    @Override
    public UUID sessionId() {
        return this.session.sessionId();
    }

    @Override
    public PlaybackInfo info() {
        return info(this.session.initiator().playerUuid());
    }

    public PlaybackInfo info(UUID playerUuid) {
        return new PlaybackInfo(sessionId(), playerUuid, Identifier.parse(this.session.id()), this.state,
            this.elapsedTicks, Identifier.parse(this.session.animationId()), this.session.animation().currentTick());
    }

    @Override
    public PlaybackState state() {
        return this.state;
    }

    @Override
    public Optional<PlaybackStopReason> stopReason() {
        return Optional.ofNullable(this.stopReason);
    }

    @Override
    public boolean pause() {
        requireControl().requireServerThread();
        if (this.state != PlaybackState.RUNNING) return false;
        this.state = PlaybackState.PAUSED;
        return true;
    }

    @Override
    public boolean resume() {
        requireControl().requireServerThread();
        if (this.state != PlaybackState.PAUSED) return false;
        this.state = PlaybackState.RUNNING;
        return true;
    }

    @Override
    public boolean finish() {
        Control control = requireControl();
        control.requireServerThread();
        if (isClosing() || this.state == PlaybackState.FINISHING) return false;
        this.state = PlaybackState.FINISHING;
        control.finish(this.session);
        return true;
    }

    @Override
    public boolean stop() {
        Control control = requireControl();
        control.requireServerThread();
        if (isClosing()) return false;
        control.stop(this.session);
        return true;
    }

    @Override
    public void onClose(Runnable cleanup) {
        requireControl().requireServerThread();
        if (isClosing()) throw new IllegalStateException("Playback is closing.");
        this.cleanup.addLast(Objects.requireNonNull(cleanup, "cleanup"));
    }

    public boolean tick(long serverTick) {
        if (isClosing() || this.state == PlaybackState.PAUSED || this.lastServerTick == serverTick) return false;
        this.lastServerTick = serverTick;
        this.elapsedTicks++;
        return true;
    }

    public boolean beginClose(PlaybackStopReason reason) {
        if (isClosing()) return false;
        this.state = PlaybackState.CLOSING;
        this.stopReason = Objects.requireNonNull(reason, "reason");
        Runnable callback;
        while ((callback = this.cleanup.pollLast()) != null) {
            try {
                callback.run();
            } catch (RuntimeException exception) {
                EmoteMod.LOGGER.warn("Failed to clean up playback {}", sessionId(), exception);
            }
        }
        return true;
    }

    public void completeClose() {
        if (this.state != PlaybackState.CLOSING) throw new IllegalStateException("Playback is not closing.");
        this.state = PlaybackState.CLOSED;
    }

    private boolean isClosing() {
        return this.state == PlaybackState.CLOSING || this.state == PlaybackState.CLOSED;
    }

    private Control requireControl() {
        return Objects.requireNonNull(this.control, "Playback control has not been bound.");
    }

    public interface Control {
        void requireServerThread();

        void finish(PlaybackSession session);

        void stop(PlaybackSession session);
    }
}
