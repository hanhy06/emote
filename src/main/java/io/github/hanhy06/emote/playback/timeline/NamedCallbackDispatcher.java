package io.github.hanhy06.emote.playback.timeline;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.EmoteCallbackEvent;
import io.github.hanhy06.emote.api.EmoteCallbackListener;
import io.github.hanhy06.emote.api.ListenerRegistration;
import net.minecraft.resources.Identifier;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NamedCallbackDispatcher {
    private final ConcurrentHashMap<Identifier, CopyOnWriteArrayList<EmoteCallbackListener>> listeners = new ConcurrentHashMap<>();
    private final Set<Identifier> warnedMissingCallbacks = ConcurrentHashMap.newKeySet();

    public ListenerRegistration addListener(Identifier name, EmoteCallbackListener listener) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(listener, "listener");
        CopyOnWriteArrayList<EmoteCallbackListener> namedListeners = this.listeners.computeIfAbsent(name, ignored -> new CopyOnWriteArrayList<>());
        namedListeners.add(listener);
        this.warnedMissingCallbacks.remove(name);
        AtomicBoolean registered = new AtomicBoolean(true);
        return () -> {
            if (!registered.compareAndSet(true, false)) return false;
            this.listeners.computeIfPresent(name, (ignored, current) -> {
                current.remove(listener);
                return current.isEmpty() ? null : current;
            });
            return true;
        };
    }

    public void dispatch(EmoteCallbackEvent event) {
        CopyOnWriteArrayList<EmoteCallbackListener> namedListeners = this.listeners.get(event.name());
        if (namedListeners == null || namedListeners.isEmpty()) {
            if (this.warnedMissingCallbacks.add(event.name())) {
                EmoteMod.LOGGER.warn("No listener is registered for emote callback {}", event.name());
            }
            return;
        }
        for (EmoteCallbackListener listener : namedListeners) {
            try {
                listener.onCallback(event);
            } catch (RuntimeException exception) {
                EmoteMod.LOGGER.warn("An emote callback listener failed while handling {}", event.name(), exception);
            }
        }
    }
}
