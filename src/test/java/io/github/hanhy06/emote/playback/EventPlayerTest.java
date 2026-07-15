package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.animation.EmoteAnimation;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EventPlayerTest {
    @Test
    void preservesLifecycleAndArrayOrder() {
        List<String> executed = new ArrayList<>();
        EventPlayer player = new EventPlayer(animation(), event -> executed.addAll(event.commands()));

        player.start();
        player.timelineTick(1);
        player.loop();
        player.stop();
        player.stop();

        assertEquals(List.of("start-a", "start-b", "tick-0", "tick-1", "loop", "stop"), executed);
    }

    private EmoteAnimation animation() {
        EmoteAnimation.Events events = new EmoteAnimation.Events(
            List.of(event("start-a"), event("start-b")),
            List.of(
                timelineEvent(0, "tick-0"),
                timelineEvent(1, "tick-1")
            ),
            List.of(event("loop")),
            List.of(event("stop"))
        );
        return new EmoteAnimation(
            Identifier.parse("test:events"),
            new EmoteAnimation.Metadata("Events", "Events", false),
            Map.of(),
            new EmoteAnimation.Timeline(2, EmoteAnimation.LoopMode.LOOP, 0, List.of(), events)
        );
    }

    private EmoteAnimation.Event event(String command) {
        return new EmoteAnimation.Event(
            new EmoteAnimation.CommandSource(EmoteAnimation.SourceType.SERVER, null),
            new EmoteAnimation.CommandOrigin(EmoteAnimation.OriginType.ROOT, null, EmoteAnimation.Vec3.ZERO),
            List.of(command)
        );
    }

    private EmoteAnimation.TimelineEvent timelineEvent(int tick, String command) {
        EmoteAnimation.Event event = event(command);
        return new EmoteAnimation.TimelineEvent(tick, event.source(), event.origin(), event.commands());
    }
}
