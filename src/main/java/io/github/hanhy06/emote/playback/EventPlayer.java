package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.animation.EmoteAnimation;

import java.util.*;

public final class EventPlayer {
    private final EmoteAnimation.Events events;
    private final EventExecutor executor;
    private final Map<Integer, List<EmoteAnimation.Event>> timelineEvents;
    private boolean started;
    private boolean stopped;

    public EventPlayer(EmoteAnimation animation, EventExecutor executor) {
        this.events = animation.timeline().events();
        this.executor = Objects.requireNonNull(executor, "executor");
        this.timelineEvents = indexTimelineEvents(this.events.timeline());
    }

    public void start() {
        if (this.started) {
            throw new IllegalStateException("Events already started");
        }
        this.started = true;
        execute(this.events.start());
        timelineTick(0);
    }

    public void timelineTick(int tick) {
        if (!this.started) {
            throw new IllegalStateException("Events have not started");
        }
        execute(this.timelineEvents.getOrDefault(tick, List.of()));
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

    private Map<Integer, List<EmoteAnimation.Event>> indexTimelineEvents(List<EmoteAnimation.TimelineEvent> events) {
        Map<Integer, List<EmoteAnimation.Event>> byTick = new LinkedHashMap<>();
        for (EmoteAnimation.TimelineEvent event : events) {
            byTick.computeIfAbsent(event.tick(), ignored -> new ArrayList<>()).add(event.event());
        }
        Map<Integer, List<EmoteAnimation.Event>> copied = new LinkedHashMap<>();
        byTick.forEach((tick, values) -> copied.put(tick, List.copyOf(values)));
        return Map.copyOf(copied);
    }

    @FunctionalInterface
    public interface EventExecutor {
        void execute(EmoteAnimation.Event event);
    }
}
