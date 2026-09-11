package io.github.hanhy06.emote;

import io.github.hanhy06.emote.api.*;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ExampleCallbacksTest {
    @Test
    void playsOriginalNotesAtTheirOffsetsOnce() {
        var melody = new ExampleCallbacks.TrumpetKoreanReveille(100, Vec3.ZERO, List.of());
        var played = new ArrayList<ExampleCallbacks.HornNote>();

        assertFalse(melody.advance(127, played::add));
        assertTrue(played.isEmpty());
        assertFalse(melody.advance(128, played::add));
        assertEquals(List.of(new ExampleCallbacks.HornNote(65, 7, 0.65F)), played);
        melody.advance(128, played::add);
        assertEquals(1, played.size());
        melody.advance(137, played::add);
        assertEquals(new ExampleCallbacks.HornNote(62, 8, 0.65F), played.getLast());

        assertTrue(melody.advance(445, played::add));
        assertEquals(64, played.size());
        assertEquals(new ExampleCallbacks.HornNote(65, 17, 0.65F), played.getLast());
        assertTrue(melody.advance(500, played::add));
        assertEquals(64, played.size());
    }

    @Test
    void concurrentMelodiesKeepIndependentStartTimes() {
        var first = new ExampleCallbacks.TrumpetKoreanReveille(0, Vec3.ZERO, List.of());
        var second = new ExampleCallbacks.TrumpetKoreanReveille(20, Vec3.ZERO, List.of());
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
    @SuppressWarnings("unchecked")
    void stoppingAndUnregisteringCancelPendingReveille() throws Exception {
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
        var field = ExampleCallbacks.class.getDeclaredField("trumpetKoreanReveilles");
        field.setAccessible(true);
        var melodies = (Map<UUID, ExampleCallbacks.TrumpetKoreanReveille>) field.get(callbacks);
        UUID performer = UUID.randomUUID();
        melodies.put(performer, new ExampleCallbacks.TrumpetKoreanReveille(0, Vec3.ZERO, List.of()));
        var stop = ExampleCallbacks.class.getDeclaredMethod("stopTrumpetKoreanReveille", UUID.class);
        stop.setAccessible(true);

        stop.invoke(callbacks, performer);
        assertTrue(melodies.isEmpty());
        melodies.put(performer, new ExampleCallbacks.TrumpetKoreanReveille(0, Vec3.ZERO, List.of()));
        assertTrue(callbacks.unregister());
        assertTrue(melodies.isEmpty());
    }
}

