package io.github.hanhy06.emote.playback;

import com.google.gson.JsonObject;
import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.TaskHandle;
import net.minecraft.resources.Identifier;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

public final class PlaybackScheduler {
    private static final int MAX_SIGNALS_PER_TICK = 1024;
    private final Runnable requireServerThread;
    private final BooleanSupplier canRun;
    private final Scope root;
    private final List<Task> tasks = new ArrayList<>();
    private final List<Subscription> subscriptions = new ArrayList<>();
    private final ArrayDeque<Signal> signals = new ArrayDeque<>();
    private long tick;
    private boolean dispatching;
    private int signalsDispatched;

    public PlaybackScheduler(Runnable requireServerThread) {
        this(requireServerThread, () -> true);
    }

    public PlaybackScheduler(Runnable requireServerThread, BooleanSupplier canRun) {
        this.requireServerThread = Objects.requireNonNull(requireServerThread, "requireServerThread");
        this.canRun = Objects.requireNonNull(canRun, "canRun");
        this.root = new Scope(null);
    }

    public Scope root() {
        return this.root;
    }

    public void tick(long tick) {
        this.requireServerThread.run();
        if (this.root.closed) return;
        beginTick(tick);
        List<Task> due = List.copyOf(this.tasks);
        for (Task task : due) {
            if (this.root.closed || !this.canRun.getAsBoolean()) break;
            if (task.cancelled || task.scope.closed || task.due > this.tick) continue;
            if (task.period == 0) task.cancel();
            else task.due = Math.addExact(this.tick, task.period);
            invoke(task.scope, task.action);
        }
        this.tasks.removeIf(task -> task.cancelled || task.scope.closed);
        drainSignals();
    }

    public void beginTick(long tick) {
        this.requireServerThread.run();
        if (tick < this.tick) throw new IllegalArgumentException("Playback ticks cannot move backwards.");
        if (this.tick != tick) this.signalsDispatched = 0;
        this.tick = tick;
    }

    private void invoke(Scope scope, Runnable action) {
        if (scope.closed || this.root.closed) return;
        boolean previous = this.dispatching;
        this.dispatching = true;
        try {
            action.run();
        } catch (RuntimeException exception) {
            if (scope == this.root) throw exception;
            scope.cancel();
            EmoteMod.LOGGER.warn("Playback action failed", exception);
        } finally {
            this.dispatching = previous;
        }
        if (!this.dispatching) drainSignals();
    }

    private void drainSignals() {
        if (this.dispatching || this.root.closed || !this.canRun.getAsBoolean()) return;
        this.dispatching = true;
        try {
            Signal signal;
            while (!this.root.closed && this.canRun.getAsBoolean() && (signal = this.signals.pollFirst()) != null) {
                if (signal.owner.closed) continue;
                if (++this.signalsDispatched > MAX_SIGNALS_PER_TICK) {
                    throw new IllegalStateException("Playback signal limit exceeded.");
                }
                Signal current = signal;
                for (Subscription subscription : List.copyOf(this.subscriptions)) {
                    if (this.root.closed || !this.canRun.getAsBoolean()) break;
                    if (!subscription.cancelled && !subscription.scope.closed && subscription.name.equals(current.name)) {
                        invoke(subscription.scope, () -> subscription.handler.accept(current.parameters.deepCopy()));
                    }
                }
            }
        } finally {
            this.dispatching = false;
            this.subscriptions.removeIf(subscription -> subscription.cancelled || subscription.scope.closed);
        }
    }

    public final class Scope implements TaskHandle {
        private final Scope parent;
        private final List<Scope> children = new ArrayList<>();
        private final ArrayDeque<Runnable> cleanup = new ArrayDeque<>();
        private final long startedAt = PlaybackScheduler.this.tick;
        private boolean closed;

        private Scope(Scope parent) {
            this.parent = parent;
        }

        public Scope child() {
            requireOpen();
            Scope child = new Scope(this);
            this.children.add(child);
            return child;
        }

        public long elapsedTicks() {
            return PlaybackScheduler.this.tick - this.startedAt;
        }

        public boolean isClosed() {
            return this.closed;
        }

        public TaskHandle afterTicks(long delay, Runnable action) {
            return schedule(delay, 0, action);
        }

        public TaskHandle everyTicks(long period, Runnable action) {
            return schedule(period, period, action);
        }

        private TaskHandle schedule(long delay, long period, Runnable action) {
            requireOpen();
            if (delay < 1) throw new IllegalArgumentException("Task delay/period must be at least one tick.");
            Task task = new Task(this, Math.addExact(PlaybackScheduler.this.tick, delay), period,
                Objects.requireNonNull(action, "action"));
            PlaybackScheduler.this.tasks.add(task);
            return task;
        }

        public void onClose(Runnable cleanup) {
            requireOpen();
            this.cleanup.addLast(Objects.requireNonNull(cleanup, "cleanup"));
        }

        public TaskHandle onSignal(Identifier name, Consumer<JsonObject> handler) {
            requireOpen();
            Subscription subscription = new Subscription(this, Objects.requireNonNull(name, "name"),
                Objects.requireNonNull(handler, "handler"));
            PlaybackScheduler.this.subscriptions.add(subscription);
            return subscription;
        }

        public void emitSignal(Identifier name, JsonObject parameters) {
            requireOpen();
            PlaybackScheduler.this.signals.addLast(new Signal(this, Objects.requireNonNull(name, "name"),
                Objects.requireNonNull(parameters, "parameters").deepCopy()));
            drainSignals();
        }

        public void invoke(Runnable action) {
            requireOpen();
            PlaybackScheduler.this.invoke(this, Objects.requireNonNull(action, "action"));
        }

        @Override
        public boolean cancel() {
            PlaybackScheduler.this.requireServerThread.run();
            if (this.closed) return false;
            this.closed = true;
            for (Scope child : List.copyOf(this.children).reversed()) child.cancel();
            Runnable callback;
            while ((callback = this.cleanup.pollLast()) != null) {
                try {
                    callback.run();
                } catch (RuntimeException exception) {
                    EmoteMod.LOGGER.warn("Playback cleanup failed", exception);
                }
            }
            if (this.parent != null) this.parent.children.remove(this);
            PlaybackScheduler.this.tasks.removeIf(task -> task.scope == this || task.scope.closed);
            PlaybackScheduler.this.subscriptions.removeIf(subscription -> subscription.scope == this || subscription.scope.closed);
            if (this == PlaybackScheduler.this.root) PlaybackScheduler.this.signals.clear();
            return true;
        }

        private void requireOpen() {
            PlaybackScheduler.this.requireServerThread.run();
            if (this.closed || PlaybackScheduler.this.root.closed) throw new IllegalStateException("Playback scope is closed.");
        }
    }

    private final class Task implements TaskHandle {
        private final Scope scope;
        private final long period;
        private final Runnable action;
        private long due;
        private boolean cancelled;

        private Task(Scope scope, long due, long period, Runnable action) {
            this.scope = scope;
            this.due = due;
            this.period = period;
            this.action = action;
        }

        public boolean cancel() {
            PlaybackScheduler.this.requireServerThread.run();
            if (this.cancelled) return false;
            this.cancelled = true;
            return true;
        }
    }

    private final class Subscription implements TaskHandle {
        private final Scope scope;
        private final Identifier name;
        private final Consumer<JsonObject> handler;
        private boolean cancelled;

        private Subscription(Scope scope, Identifier name, Consumer<JsonObject> handler) {
            this.scope = scope;
            this.name = name;
            this.handler = handler;
        }

        public boolean cancel() {
            PlaybackScheduler.this.requireServerThread.run();
            if (this.cancelled) return false;
            this.cancelled = true;
            return true;
        }
    }

    private record Signal(Scope owner, Identifier name, JsonObject parameters) {}
}
