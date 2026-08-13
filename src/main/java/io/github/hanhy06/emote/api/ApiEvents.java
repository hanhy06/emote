package io.github.hanhy06.emote.api;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.emote.EmoteDefinition;
import io.github.hanhy06.emote.playback.PlaybackParticipant;
import io.github.hanhy06.emote.playback.PlaybackSession;
import io.github.hanhy06.emote.playback.PlaybackStateListener;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ApiEvents implements PlaybackStateListener {
    private final CopyOnWriteArrayList<EmotePlayListener> playListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<EmotePlaybackListener> playbackListeners = new CopyOnWriteArrayList<>();

    public ListenerRegistration addPlayListener(EmotePlayListener listener) {
        return register(this.playListeners, Objects.requireNonNull(listener, "listener"));
    }

    public ListenerRegistration addPlaybackListener(EmotePlaybackListener listener) {
        return register(this.playbackListeners, Objects.requireNonNull(listener, "listener"));
    }

    public Component beforePlay(ServerPlayer player, EmoteDefinition emote, PlaySource source) {
        EmotePlayEvent event = new EmotePlayEvent(player, toInfo(emote), source);
        for (EmotePlayListener listener : this.playListeners) {
            try {
                listener.beforePlay(event);
            } catch (RuntimeException exception) {
                Emote.LOGGER.warn("An emote play listener failed", exception);
                event.cancel(Component.literal("Emote playback was cancelled because a listener failed."));
            }
            if (event.isCancelled()) {
                break;
            }
        }
        return event.isCancelled() ? event.cancellationMessage() : null;
    }

    @Override
    public void onStarted(ServerPlayer player, PlaybackSession session, PlaybackParticipant participant) {
        PlaybackInfo playback = toPlaybackInfo(session, participant);
        for (EmotePlaybackListener listener : this.playbackListeners) {
            try {
                listener.onStarted(playback);
            } catch (RuntimeException exception) {
                Emote.LOGGER.warn("An emote playback listener failed while handling start", exception);
            }
        }
    }

    @Override
    public void onStopped(
        ServerPlayer player,
        PlaybackSession session,
        PlaybackParticipant participant,
        PlaybackStopReason reason
    ) {
        PlaybackInfo playback = toPlaybackInfo(session, participant);
        for (EmotePlaybackListener listener : this.playbackListeners) {
            try {
                listener.onStopped(playback, reason);
            } catch (RuntimeException exception) {
                Emote.LOGGER.warn("An emote playback listener failed while handling stop", exception);
            }
        }
    }

    public static EmoteInfo toInfo(EmoteDefinition emote) {
        return new EmoteInfo(
            Identifier.parse(emote.id()),
            emote.metadata(),
            emote.playerBehavior(),
            emote.durationTicks(),
            emote.cooldownTicks(),
            emote.loopMode()
        );
    }

    public static PlaybackInfo toPlaybackInfo(PlaybackSession session, PlaybackParticipant participant) {
        return new PlaybackInfo(
            participant.playerUuid(),
            Identifier.parse(session.id()),
            session.timeline().currentTick()
        );
    }

    private static <T> ListenerRegistration register(CopyOnWriteArrayList<T> listeners, T listener) {
        listeners.add(listener);
        AtomicBoolean registered = new AtomicBoolean(true);
        return () -> registered.compareAndSet(true, false) && listeners.remove(listener);
    }
}
