package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.content.CompiledTimeline;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;

import java.util.List;
import java.util.Objects;

public final class EventPlayer {
    private final EmoteAnimation.Events events;
    private final EventExecutor executor;
    private final CompiledTimeline compiledTimeline;

    private boolean started;
    private boolean stopped;

    public EventPlayer(EmoteAnimation animation, EventExecutor executor) {
        this(CompiledTimeline.compile(animation), executor);
    }

    public EventPlayer(CompiledTimeline compiledTimeline, EventExecutor executor) {
        this.compiledTimeline = Objects.requireNonNull(compiledTimeline, "compiledTimeline");
        this.events = compiledTimeline.animation().timeline().events();
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public void start() {
        if (this.started) {
            throw new IllegalStateException("Events already started");
        }
        this.started = true;
        execute(this.events.start());
    }

    public void timelineTick(int tick) {
        if (!this.started) {
            throw new IllegalStateException("Events have not started");
        }
        execute(this.compiledTimeline.timelineEvents(tick));
    }

    void timelineEvents(List<EmoteAnimation.Event> events) {
        if (!this.started) {
            throw new IllegalStateException("Events have not started");
        }
        execute(events);
    }

    public void loop() {
        if (!this.started || this.stopped) {
            throw new IllegalStateException("Events are not active");
        }
        execute(this.events.loop());
    }

    public void stop() {
        if (!this.started || this.stopped) {
            return;
        }
        this.stopped = true;
        execute(this.events.stop());
    }

    private void execute(List<EmoteAnimation.Event> eventList) {
        for (EmoteAnimation.Event event : eventList) {
            this.executor.execute(event);
        }
    }

    @FunctionalInterface
    public interface EventExecutor {
        void execute(EmoteAnimation.Event event);
    }
}
