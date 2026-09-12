package io.github.hanhy06.emote;

import io.github.hanhy06.emote.api.*;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.content.loader.AnimationJsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
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
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ExampleCallbacksTest {
    @Test
    void playsCanCanNotesAtTheirOffsetsOnce() {
        var melody = new ExampleCallbacks.TrumpetCanCan(100, Vec3.ZERO, List.of());
        var played = new ArrayList<ExampleCallbacks.HornNote>();

        assertFalse(melody.advance(127, played::add));
        assertTrue(played.isEmpty());
        assertFalse(melody.advance(128, played::add));
        assertEquals(List.of(new ExampleCallbacks.HornNote(55, 6, 0.65F)), played);
        melody.advance(128, played::add);
        assertEquals(1, played.size());
        melody.advance(133, played::add);
        assertEquals(1, played.size());
        melody.advance(134, played::add);
        assertEquals(List.of(new ExampleCallbacks.HornNote(55, 6, 0.65F), new ExampleCallbacks.HornNote(55, 6, 0.65F)), played);
        melody.advance(140, played::add);
        assertEquals(new ExampleCallbacks.HornNote(57, 3, 0.65F), played.getLast());

        assertTrue(melody.advance(500, played::add));
        assertEquals(99, played.size());
        assertTrue(melody.advance(506, played::add));
        assertEquals(99, played.size());
        assertEquals(new ExampleCallbacks.HornNote(55, 6, 0.65F), played.getLast());
        assertTrue(melody.advance(535, played::add));
        assertEquals(99, played.size());
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
        var noteEndTicks = new ArrayList<Integer>();
        var retriggerTicks = List.of(34, 130, 226, 322);
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
                noteEndTicks.add(at + note.durationTicks());
                double pitch = 440.0 * Math.pow(2.0, (note.midi() + 4.9 - 12.0 - 69.0) / 12.0) / 130.8;
                assertTrue(pitch >= 0.5 && pitch <= 2.0, "Can-Can note must fit the vanilla sound pitch range");
                assertTrue(note.durationTicks() <= 7, "Every breath must fit the user's 7-tick cap");
                if (retriggerTicks.contains(at)) {
                    assertEquals(new ExampleCallbacks.HornNote(55, 6, 0.65F), note);
                    assertEquals(at - 6, noteTicks.get(noteTicks.size() - 2));
                }
                assertTrue(at + note.durationTicks() < 419, "Notes must finish before the trumpet disappears");
            });
        }
        assertEquals(99, noteTicks.size());
        assertFalse(noteTicks.contains(406), "The final note must not be played a second time");
        assertTrue(noteTicks.containsAll(retriggerTicks));
        assertTrue(particleTicks.isEmpty(), "Note particles are sent with sound packets, not timeline commands");
        assertEquals(406, noteEndTicks.getLast());
        assertEquals(noteTicks.subList(1, noteTicks.size()), noteEndTicks.subList(0, noteEndTicks.size() - 1), "Each note sustains until the next one without a forced rest");
    }

    @Test
    @SuppressWarnings("unchecked")
    void outOfRangePitchesDoNotThrowAndStoppingCancelsCanCan() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
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
        var playHorn = ExampleCallbacks.class.getDeclaredMethod("playHorn", UUID.class, Vec3.class, ExampleCallbacks.HornNote.class, List.class);
        playHorn.setAccessible(true);
        var listenerConstructor = Class.forName("io.github.hanhy06.emote.ExampleCallbacks$HornListener").getDeclaredConstructor(UUID.class, Consumer.class);
        listenerConstructor.setAccessible(true);
        var packets = new ArrayList<Packet<?>>();
        Object listener = listenerConstructor.newInstance(UUID.randomUUID(), (Consumer<Packet<?>>) packets::add);
        var packetMelody = new ExampleCallbacks.TrumpetCanCan(0, Vec3.ZERO, List.of());
        int sounds = 0;
        for (int tick = 0; tick <= 435; tick++) {
            var notes = new ArrayList<ExampleCallbacks.HornNote>();
            packetMelody.advance(tick, notes::add);
            for (var note : notes) {
                packets.clear();
                playHorn.invoke(callbacks, performer, Vec3.ZERO, note, List.of(listener));
                assertInstanceOf(ClientboundSoundPacket.class, packets.get(packets.size() - 2));
                var particle = assertInstanceOf(ClientboundLevelParticlesPacket.class, packets.getLast());
                assertEquals(ParticleTypes.NOTE, particle.getParticle());
                assertEquals(0, particle.getCount());
                assertEquals(1, packets.stream().filter(ClientboundLevelParticlesPacket.class::isInstance).count());
                sounds++;
            }
        }
        assertEquals(99, sounds, "Every actual note, including retriggers, sends exactly one particle immediately after its sound");
        packets.clear();
        playHorn.invoke(callbacks, UUID.randomUUID(), Vec3.ZERO, new ExampleCallbacks.HornNote(55, 6, 0.65F), List.of(listener));
        assertTrue(packets.isEmpty(), "A suppressed sound must not produce a particle");
        assertDoesNotThrow(() -> playHorn.invoke(callbacks, performer, Vec3.ZERO, new ExampleCallbacks.HornNote(127, 12, 0.65F), List.of()));
        assertDoesNotThrow(() -> playHorn.invoke(callbacks, performer, Vec3.ZERO, new ExampleCallbacks.HornNote(0, 12, 0.65F), List.of()));
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

