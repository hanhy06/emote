package io.github.hanhy06.emote;

import io.github.hanhy06.emote.api.*;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.content.loader.AnimationJsonParser;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ExampleCallbacksTest {
    @Test
    void playsCanCanNotesAtTheirOffsetsOnce() {
        var melody = new ExampleCallbacks.TrumpetCanCan(100, Vec3.ZERO, List.of());
        var played = new ArrayList<ExampleCallbacks.HornNote>();

        assertFalse(melody.advance(127, played::add));
        assertTrue(played.isEmpty());
        assertFalse(melody.advance(128, played::add));
        assertEquals(List.of(new ExampleCallbacks.HornNote(55, 11, 0.65F)), played);
        melody.advance(128, played::add);
        assertEquals(1, played.size());
        melody.advance(140, played::add);
        assertEquals(new ExampleCallbacks.HornNote(57, 2, 0.65F), played.getLast());

        assertTrue(melody.advance(500, played::add));
        assertEquals(95, played.size());
        assertEquals(new ExampleCallbacks.HornNote(55, 11, 0.65F), played.getLast());
        assertTrue(melody.advance(535, played::add));
        assertEquals(95, played.size());
    }

    @Test
    void concurrentMelodiesKeepIndependentStartTimes() {
        var first = new ExampleCallbacks.TrumpetCanCan(0, Vec3.ZERO, List.of());
        var second = new ExampleCallbacks.TrumpetCanCan(20, Vec3.ZERO, List.of());
        var firstNotes = new ArrayList<ExampleCallbacks.HornNote>();
        var secondNotes = new ArrayList<ExampleCallbacks.HornNote>();

        first.advance(28, firstNotes::add);
        second.advance(28, secondNotes::add);
        assertEquals(1, firstNotes.size());
        assertTrue(secondNotes.isEmpty());
        second.advance(48, secondNotes::add);
        assertEquals(firstNotes, secondNotes);
    }

    @Test
    void canCanSampleLoadsAndParticlesFollowPlayableNotes() throws Exception {
        var animation = new AnimationJsonParser().parse(Path.of("docs/sample/music/emote.trumpet_can_can.json")).animation();
        var melody = new ExampleCallbacks.TrumpetCanCan(0, Vec3.ZERO, List.of());
        var noteTicks = new ArrayList<Integer>();
        var particleTicks = animation.timeline().events().timeline().stream()
            .filter(event -> event.commands().stream().anyMatch(command -> command.contains("particle minecraft:note ")))
            .map(EmoteAnimation.TimelineEvent::tick)
            .toList();

        assertEquals("emote:trumpet_can_can", animation.id().toString());
        assertEquals(435, animation.timeline().durationTicks());
        assertEquals(ExampleCallbacks.TRUMPET_CAN_CAN_CALLBACK_ID, animation.timeline().events().start().getFirst().callbacks().getFirst().name());
        for (int tick = 0; tick <= animation.timeline().durationTicks(); tick++) {
            int at = tick;
            melody.advance(tick, note -> {
                noteTicks.add(at);
                double pitch = 440.0 * Math.pow(2.0, (note.midi() + 4.5 - 12.0 - 69.0) / 12.0) / 130.8;
                assertTrue(pitch >= 0.5 && pitch <= 2.0, "Can-Can note must fit the vanilla sound pitch range");
                assertTrue(at + note.durationTicks() < 419, "Notes must finish before the trumpet disappears");
            });
        }
        assertEquals(95, noteTicks.size());
        assertEquals(noteTicks, particleTicks);
    }

    @Test
    @SuppressWarnings("unchecked")
    void stoppingAndUnregisteringCancelPendingCanCan() throws Exception {
        EmoteApi previous = EmoteApi.INSTANCE;
        EmoteApi.INSTANCE = null;
        EmoteApi api;
        try {
            api = new EmoteApi() {
                public PlayResult play(ServerPlayer player, Identifier id) { throw new UnsupportedOperationException(); }
                public boolean stop(ServerPlayer player) { throw new UnsupportedOperationException(); }
                public EmoteRegistration register(EmoteAnimation animation) { throw new UnsupportedOperationException(); }
                public Optional<EmoteInfo> find(Identifier id) { return Optional.empty(); }
                public List<EmoteInfo> getAll() { return List.of(); }
                public Optional<PlaybackInfo> getPlayback(ServerPlayer player) { return Optional.empty(); }
                public ListenerRegistration addPlayListener(EmotePlayListener listener) { return () -> true; }
                public ListenerRegistration addPlaybackListener(EmotePlaybackListener listener) { return () -> true; }
                public ListenerRegistration addCallbackListener(Identifier name, EmoteCallbackListener listener) { return () -> true; }
            };
        } finally {
            EmoteApi.INSTANCE = previous;
        }
        ExampleCallbacks callbacks = ExampleCallbacks.registerAll(api);
        var field = ExampleCallbacks.class.getDeclaredField("trumpetCanCans");
        field.setAccessible(true);
        var melodies = (Map<UUID, ExampleCallbacks.TrumpetCanCan>) field.get(callbacks);
        UUID performer = UUID.randomUUID();
        melodies.put(performer, new ExampleCallbacks.TrumpetCanCan(0, Vec3.ZERO, List.of()));
        var stop = ExampleCallbacks.class.getDeclaredMethod("stopTrumpetCanCan", UUID.class);
        stop.setAccessible(true);

        stop.invoke(callbacks, performer);
        assertTrue(melodies.isEmpty());
        melodies.put(performer, new ExampleCallbacks.TrumpetCanCan(0, Vec3.ZERO, List.of()));
        assertTrue(callbacks.unregister());
        assertTrue(melodies.isEmpty());
    }
}

