package io.github.hanhy06.emote.application;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.*;
import io.github.hanhy06.emote.content.PlayableEmote;
import io.github.hanhy06.emote.playback.PlaybackStateListener;
import io.github.hanhy06.emote.playback.session.PlaybackParticipant;
import io.github.hanhy06.emote.playback.session.PlaybackSession;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ApiEventDispatcher implements PlaybackStateListener {
    private final CopyOnWriteArrayList<EmotePlayListener> playListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<EmotePlaybackListener> playbackListeners = new CopyOnWriteArrayList<>();
    private final Map<StartKey, StartDispatch> startingPlaybacks = new HashMap<>();

    public ListenerRegistration addPlayListener(EmotePlayListener listener) {
        return register(this.playListeners, Objects.requireNonNull(listener, "listener"));
    }

    public ListenerRegistration addPlaybackListener(EmotePlaybackListener listener) {
        return register(this.playbackListeners, Objects.requireNonNull(listener, "listener"));
    }

    public Component beforePlay(ServerPlayer player, PlayableEmote emote, PlaySource source) {
        EmotePlayEvent event = new EmotePlayEvent(player, toInfo(emote), source);
        for (EmotePlayListener listener : this.playListeners) {
            try {
                listener.beforePlay(event);
            } catch (RuntimeException exception) {
                EmoteMod.LOGGER.warn("Emote play listener {} failed", listener.getClass().getName(), exception);
                event.cancel(Component.literal("Something went wrong while checking this emote."));
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
        StartKey key = new StartKey(session.sessionId(), participant.playerUuid());
        StartDispatch dispatch = new StartDispatch(List.copyOf(this.playbackListeners));
        if (this.startingPlaybacks.putIfAbsent(key, dispatch) != null) {
            throw new IllegalStateException("Playback start is already being dispatched: " + session.sessionId());
        }
        try {
            for (EmotePlaybackListener listener : dispatch.listeners()) {
                dispatch.markNotified();
                try {
                    listener.onStarted(playback);
                } catch (RuntimeException exception) {
                    EmoteMod.LOGGER.warn("Emote playback listener {} failed while handling start", listener.getClass().getName(), exception);
                }
                if (dispatch.stopped()) {
                    break;
                }
            }
        } finally {
            this.startingPlaybacks.remove(key, dispatch);
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
        StartDispatch startDispatch = this.startingPlaybacks.get(new StartKey(session.sessionId(), participant.playerUuid()));
        List<EmotePlaybackListener> listeners;
        if (startDispatch == null) {
            listeners = List.copyOf(this.playbackListeners);
        } else {
            startDispatch.markStopped();
            listeners = startDispatch.notifiedListeners();
        }
        for (EmotePlaybackListener listener : listeners) {
            try {
                listener.onStopped(playback, reason);
            } catch (RuntimeException exception) {
                EmoteMod.LOGGER.warn("Emote playback listener {} failed while handling stop", listener.getClass().getName(), exception);
            }
        }
    }

    public static EmoteInfo toInfo(PlayableEmote emote) {
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
            session.animation().currentTick()
        );
    }

    private static <T> ListenerRegistration register(CopyOnWriteArrayList<T> listeners, T listener) {
        listeners.add(listener);
        AtomicBoolean registered = new AtomicBoolean(true);
        return () -> registered.compareAndSet(true, false) && listeners.remove(listener);
    }

    private record StartKey(UUID sessionId, UUID playerUuid) {
    }

    private static final class StartDispatch {
        private final List<EmotePlaybackListener> listeners;
        private int notifiedCount;
        private boolean stopped;

        private StartDispatch(List<EmotePlaybackListener> listeners) {
            this.listeners = listeners;
        }

        private List<EmotePlaybackListener> listeners() {
            return this.listeners;
        }

        private void markNotified() {
            this.notifiedCount++;
        }

        private List<EmotePlaybackListener> notifiedListeners() {
            return this.listeners.subList(0, this.notifiedCount);
        }

        private void markStopped() {
            this.stopped = true;
        }

        private boolean stopped() {
            return this.stopped;
        }
    }
}
