package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.api.EmoteCallbacks;
import io.github.hanhy06.emote.api.Registration;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import io.github.hanhy06.emote.api.EmoteCallback;

public final class CallbackRegistry {
    private final ConcurrentHashMap<Identifier, RegisteredCallbacks> registrations = new ConcurrentHashMap<>();

    public Registration register(Identifier id, EmoteCallbacks callbacks) {
        RegisteredCallbacks registration = new RegisteredCallbacks(Objects.requireNonNull(id, "id"), Objects.requireNonNull(callbacks, "callbacks"));
        if (this.registrations.putIfAbsent(id, registration) != null) {
            throw new IllegalArgumentException("Callbacks are already registered: " + id);
        }
        return registration;
    }

    public List<Binding> resolve(List<EmoteCallback> definitions) {
        List<Binding> bindings = new ArrayList<>();
        for (EmoteCallback definition : definitions) {
            RegisteredCallbacks registration = this.registrations.get(definition.name());
            if (registration == null) throw new IllegalArgumentException("Unknown emote callback: " + definition.name());
            bindings.add(new Binding(registration.callbacks, definition.payload()));
        }
        return List.copyOf(bindings);
    }

    public record Binding(EmoteCallbacks callbacks, String payload) {
        public Binding {
            Objects.requireNonNull(callbacks, "callbacks");
            Objects.requireNonNull(payload, "payload");
        }
    }

    private final class RegisteredCallbacks implements Registration {
        private final Identifier id;
        private final EmoteCallbacks callbacks;

        private RegisteredCallbacks(Identifier id, EmoteCallbacks callbacks) {
            this.id = id;
            this.callbacks = callbacks;
        }

        public Identifier getId() { return this.id; }
        public boolean isRegistered() { return CallbackRegistry.this.registrations.get(this.id) == this; }
        public boolean unregister() { return CallbackRegistry.this.registrations.remove(this.id, this); }
    }
}
