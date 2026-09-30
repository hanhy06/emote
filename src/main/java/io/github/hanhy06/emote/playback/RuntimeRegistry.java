package io.github.hanhy06.emote.playback;

import com.google.gson.JsonObject;
import io.github.hanhy06.emote.api.EmoteAction;
import io.github.hanhy06.emote.api.RuntimeRegistration;
import io.github.hanhy06.emote.api.TaskHandle;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.function.Consumer;

public final class RuntimeRegistry {
    private final Map<Identifier, Registration> actions = new LinkedHashMap<>();
    private final Runnable requireServerThread;
    private final Consumer<PlaybackExecution> stopPlayback;

    public RuntimeRegistry(Runnable requireServerThread, Consumer<PlaybackExecution> stopPlayback) {
        this.requireServerThread = Objects.requireNonNull(requireServerThread, "requireServerThread");
        this.stopPlayback = Objects.requireNonNull(stopPlayback, "stopPlayback");
    }

    public RuntimeRegistration registerAction(Identifier id, EmoteAction action) {
        this.requireServerThread.run();
        Registration registration = new Registration(Objects.requireNonNull(id, "id"), Objects.requireNonNull(action, "action"));
        if (this.actions.putIfAbsent(id, registration) != null) {
            throw new IllegalArgumentException("Action is already registered: " + id);
        }
        return registration;
    }

    public TaskHandle invokeAction(PlaybackExecution execution, Identifier id, JsonObject parameters, Vec3 origin) {
        this.requireServerThread.run();
        Registration registration = this.actions.get(Objects.requireNonNull(id, "id"));
        if (registration == null) throw new IllegalArgumentException("Action is not registered: " + id);
        return registration.invoke(execution, parameters, origin);
    }

    private final class Registration implements RuntimeRegistration {
        private final Identifier id;
        private final EmoteAction action;
        private final Map<PlaybackExecution, Integer> executions = new IdentityHashMap<>();

        private Registration(Identifier id, EmoteAction action) {
            this.id = id;
            this.action = action;
        }

        public Identifier id() { return this.id; }
        public boolean isRegistered() { return RuntimeRegistry.this.actions.get(this.id) == this; }

        public boolean unregister() {
            RuntimeRegistry.this.requireServerThread.run();
            if (!RuntimeRegistry.this.actions.remove(this.id, this)) return false;
            for (PlaybackExecution execution : List.copyOf(this.executions.keySet())) {
                RuntimeRegistry.this.stopPlayback.accept(execution);
            }
            return true;
        }

        private TaskHandle invoke(PlaybackExecution execution, JsonObject parameters, Vec3 origin) {
            JsonObject arguments = Objects.requireNonNull(parameters, "parameters").deepCopy();
            var scope = execution.scheduler().root().child();
            this.executions.merge(execution, 1, Integer::sum);
            scope.onClose(() -> {
                Integer count = this.executions.get(execution);
                if (count != null && count > 1) this.executions.put(execution, count - 1);
                else this.executions.remove(execution);
            });
            scope.invoke(() -> this.action.run(execution.context(scope, origin), arguments));
            return scope;
        }
    }
}
