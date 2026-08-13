package io.github.hanhy06.emote.playback;

import java.util.Objects;

public final class PlaybackTrack {
    private final TimelinePlayer timeline;
    private final EventPlayer events;

    public PlaybackTrack(TimelinePlayer timeline, EventPlayer events) {
        this.timeline = Objects.requireNonNull(timeline, "timeline");
        this.events = Objects.requireNonNull(events, "events");
    }

    public TimelinePlayer timeline() {
        return this.timeline;
    }

    public EventPlayer events() {
        return this.events;
    }

    public int currentTick() {
        return this.timeline.currentTick();
    }

    public void startEvents() {
        this.events.start();
        if (this.timeline.currentTick() == 0) {
            this.events.timelineTick(0);
        }
    }

    public TimelinePlayer.AdvanceResult advance() {
        return advance(true);
    }

    public TimelinePlayer.AdvanceResult advance(boolean continueAfterLoopBoundary) {
        int previousTick = this.timeline.currentTick();
        TimelinePlayer.AdvanceResult result = this.timeline.advance();
        if (result != TimelinePlayer.AdvanceResult.RESTARTED && this.timeline.currentTick() != previousTick) {
            this.events.timelineTick(this.timeline.currentTick());
        }
        if (result == TimelinePlayer.AdvanceResult.LOOP_BOUNDARY) {
            this.events.loop();
            if (continueAfterLoopBoundary) {
                result = this.timeline.continueAfterLoopEvent();
            }
        }
        if (result == TimelinePlayer.AdvanceResult.RESTARTED) {
            this.events.timelineTick(0);
        }
        return result;
    }

    public void stop() {
        this.events.stop();
    }
}
