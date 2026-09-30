package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.api.CallbackRegistration;
import io.github.hanhy06.emote.api.EmoteCallbacks;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

public final class CallbackRegistry {
    private final ConcurrentHashMap<Identifier, Registration> registrations = new ConcurrentHashMap<>();

    public CallbackRegistration register(Identifier id, EmoteCallbacks callbacks) {
        Registration registration = new Registration(Objects.requireNonNull(id, "id"), Objects.requireNonNull(callbacks, "callbacks"));
        if (this.registrations.putIfAbsent(id, registration) != null) {
            throw new IllegalArgumentException("Callbacks are already registered: " + id);
        }
        return registration;
    }

    public @Nullable EmoteCallbacks find(Identifier id) {
        Registration registration = this.registrations.get(id);
        return registration == null ? null : registration.callbacks;
    }

    public List<Binding> resolve(List<EmoteAnimation.Callback> definitions) {
        List<Binding> bindings = new ArrayList<>();
        for (EmoteAnimation.Callback definition : definitions) {
            EmoteCallbacks callbacks = find(definition.name());
            if (callbacks == null) throw new IllegalArgumentException("Unknown emote callback: " + definition.name());
            bindings.add(new Binding(callbacks, definition.payload()));
        }
        return List.copyOf(bindings);
    }

    public record Binding(EmoteCallbacks callbacks, String payload) {
        public Binding {
            Objects.requireNonNull(callbacks, "callbacks");
            Objects.requireNonNull(payload, "payload");
        }
    }

    private final class Registration implements CallbackRegistration {
        private final Identifier id;
        private final EmoteCallbacks callbacks;

        private Registration(Identifier id, EmoteCallbacks callbacks) {
            this.id = id;
            this.callbacks = callbacks;
        }

        public Identifier id() { return this.id; }
        public boolean isRegistered() { return CallbackRegistry.this.registrations.get(this.id) == this; }
        public boolean unregister() { return CallbackRegistry.this.registrations.remove(this.id, this); }
    }
}
