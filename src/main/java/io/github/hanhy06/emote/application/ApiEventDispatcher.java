package io.github.hanhy06.emote.application;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.*;
import io.github.hanhy06.emote.playback.PlaybackEngine;
import io.github.hanhy06.emote.playback.session.PlaybackSession;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.hanhy06.emote.content.PreparedEmote;

public final class ApiEventDispatcher implements PlaybackEngine.Lifecycle {
    private final CopyOnWriteArrayList<EmotePlayListener> playListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<EmotePlaybackListener> playbackListeners = new CopyOnWriteArrayList<>();
    private final Map<UUID, StartDispatch> startingPlaybacks = new HashMap<>();

    public ListenerRegistration addPlayListener(EmotePlayListener listener) {
        return register(this.playListeners, Objects.requireNonNull(listener, "listener"));
    }

    public ListenerRegistration addPlaybackListener(EmotePlaybackListener listener) {
        return register(this.playbackListeners, Objects.requireNonNull(listener, "listener"));
    }

    public Component beforePlay(ServerPlayer player, PreparedEmote emote, PlaySource source) {
        EmotePlayEvent event = new EmotePlayEvent(player, emote.info(), source);
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
    public void onStarted(PlaybackSession session) {
        PlaybackInfo playback = session.info();
        UUID key = session.sessionId();
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
        PlaybackSession session,
        PlaybackStopReason reason
    ) {
        PlaybackInfo playback = session.info();
        StartDispatch startDispatch = this.startingPlaybacks.get(session.sessionId());
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

    private static <T> ListenerRegistration register(CopyOnWriteArrayList<T> listeners, T listener) {
        listeners.add(listener);
        AtomicBoolean registered = new AtomicBoolean(true);
        return () -> registered.compareAndSet(true, false) && listeners.remove(listener);
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
