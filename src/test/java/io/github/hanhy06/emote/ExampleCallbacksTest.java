package io.github.hanhy06.emote;

import io.github.hanhy06.emote.api.*;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.api.sequence.EmoteSequence;
import io.github.hanhy06.emote.content.loader.AnimationJsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.*;
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
        assertEquals(List.of(new ExampleCallbacks.HornNote(28, 55, 6, 0.65F)), played);
        melody.advance(128, played::add);
        assertEquals(1, played.size());
        melody.advance(133, played::add);
        assertEquals(1, played.size());
        melody.advance(134, played::add);
        assertEquals(List.of(new ExampleCallbacks.HornNote(28, 55, 6, 0.65F), new ExampleCallbacks.HornNote(34, 55, 6, 0.65F)), played);
        melody.advance(140, played::add);
        assertEquals(new ExampleCallbacks.HornNote(40, 57, 3, 0.65F), played.getLast());

        assertTrue(melody.advance(500, played::add));
        assertEquals(99, played.size());
        assertTrue(melody.advance(506, played::add));
        assertEquals(99, played.size());
        assertEquals(new ExampleCallbacks.HornNote(400, 55, 6, 0.65F), played.getLast());
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
        var animation = new AnimationJsonParser().parse(Path.of("docs/sample/music/music.trumpet_can_can.json")).animation();
        var melody = new ExampleCallbacks.TrumpetCanCan(0, Vec3.ZERO, List.of());
        var noteTicks = new ArrayList<Integer>();
        var noteEndTicks = new ArrayList<Integer>();
        var retriggerTicks = List.of(34, 130, 226, 322);
        var particleTicks = animation.timeline().events().timeline().stream()
            .filter(event -> event.commands().stream().anyMatch(command -> command.contains("particle minecraft:note ")))
            .map(EmoteAnimation.TimelineEvent::tick)
            .toList();

        assertEquals("music:trumpet_can_can", animation.id().toString());
        assertEquals(435, animation.timeline().durationTicks());
        assertTrue(animation.timeline().events().start().isEmpty());
        for (int tick = 0; tick <= animation.timeline().durationTicks(); tick++) {
            int at = tick;
            melody.advance(tick, note -> {
                noteTicks.add(at);
                noteEndTicks.add(at + note.durationTicks());
                double pitch = 440.0 * Math.pow(2.0, (note.midi() + 4.9 - 12.0 - 69.0) / 12.0) / 130.8;
                assertTrue(pitch >= 0.5 && pitch <= 2.0, "Can-Can note must fit the vanilla sound pitch range");
                assertTrue(note.durationTicks() <= 7, "Every breath must fit the user's 7-tick cap");
                if (retriggerTicks.contains(at)) {
                    assertEquals(new ExampleCallbacks.HornNote(at, 55, 6, 0.65F), note);
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
    void samplesLoadWithoutJsonCallbacksAndKeepTheirNodes() throws Exception {
        var parser = new AnimationJsonParser();
        var bat = parser.parse(Path.of("docs/sample/emote.bat.json")).animation();
        var butterfly = parser.parse(Path.of("docs/sample/sit/sit.idle_butterfly.json")).animation();
        assertEquals(Identifier.parse("emote:bat"), bat.id());
        assertTrue(bat.nodes().containsKey("bat"));
        assertEquals(List.of(new EmoteAnimation.Callback(ExampleCallbacks.BAT_CALLBACK_ID, "bat")), bat.callbacks());
        assertTrue(bat.timeline().events().timeline().stream().allMatch(event -> !event.commands().isEmpty()));
        assertEquals(Identifier.parse("sit:idle_butterfly"), butterfly.id());
        var anchor = assertInstanceOf(EmoteAnimation.AnchorNode.class, butterfly.nodes().get("butterfly"));
        assertEquals("butterfly_x", anchor.parentId());
        assertEquals(List.of(new EmoteAnimation.Callback(ExampleCallbacks.IDLE_BUTTERFLY_CALLBACK_ID, "butterfly")), butterfly.callbacks());
        assertTrue(butterfly.timeline().events().timeline().isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void packetNotesKeepTimingAndCloseStopsOnlyItsSession() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        EmoteApi previous = EmoteApi.INSTANCE;
        EmoteApi.INSTANCE = null;
        EmoteApi api;
        Map<Identifier, EmoteCallbacks> registeredCallbacks = new java.util.HashMap<>();
        try {
            api = new EmoteApi() {
                public PlayResult play(ServerPlayer player, Identifier id) { throw new UnsupportedOperationException(); }
                public PlayResult play(ServerPlayer player, Identifier id, PlayOptions options) { throw new UnsupportedOperationException(); }
                public boolean stop(ServerPlayer player) { throw new UnsupportedOperationException(); }
                public boolean stop(UUID sessionId) { throw new UnsupportedOperationException(); }
                public boolean setPlacement(UUID sessionId, PlaybackPlacement placement) { throw new UnsupportedOperationException(); }
                public Optional<Vec3> getNodeWorldPosition(UUID sessionId, String nodeId) { return Optional.empty(); }
                public Registration register(EmoteAnimation animation) { throw new UnsupportedOperationException(); }
                public Registration register(EmoteSequence sequence) { throw new UnsupportedOperationException(); }
                public Optional<EmoteInfo> find(Identifier id) { return Optional.empty(); }
                public List<EmoteInfo> getAll() { return List.of(); }
                public Optional<PlaybackInfo> getPlayback(ServerPlayer player) { return Optional.empty(); }
                public Optional<PlaybackInfo> getPlayback(UUID sessionId) { return Optional.empty(); }
                public Optional<PlaybackTimeline> getTimeline(UUID sessionId) { return Optional.empty(); }
                public Registration registerCallbacks(Identifier id, EmoteCallbacks callbacks) {
                    registeredCallbacks.put(id, callbacks);
                    return new Registration() {
                        private boolean registered = true;
                        public Identifier id() { return id; }
                        public boolean isRegistered() { return registered; }
                        public boolean unregister() { boolean previous = registered; registered = false; return previous; }
                    };
                }
                public ListenerRegistration addPlayListener(EmotePlayListener listener) { return () -> true; }
                public ListenerRegistration addPlaybackListener(EmotePlaybackListener listener) { return () -> true; }
            };
        } finally {
            EmoteApi.INSTANCE = previous;
        }
        ExampleCallbacks callbacks = ExampleCallbacks.registerAll(api);
        var field = ExampleCallbacks.class.getDeclaredField("activeMelodies");
        field.setAccessible(true);
        var melodies = (java.util.Set<ExampleCallbacks.TrumpetCanCan>) field.get(callbacks);
        var playHorn = ExampleCallbacks.class.getDeclaredMethod("playHorn", ExampleCallbacks.TrumpetCanCan.class, ExampleCallbacks.HornNote.class, long.class);
        playHorn.setAccessible(true);
        var listenerConstructor = Class.forName("io.github.hanhy06.emote.ExampleCallbacks$HornListener").getDeclaredConstructor(UUID.class, Consumer.class);
        listenerConstructor.setAccessible(true);
        var packets = new ArrayList<Packet<?>>();
        Object listener = listenerConstructor.newInstance(UUID.randomUUID(), (Consumer<Packet<?>>) packets::add);
        var packetMelody = new ExampleCallbacks.TrumpetCanCan(0, Vec3.ZERO, List.of());
        var soundingMelody = new ExampleCallbacks.TrumpetCanCan(0, Vec3.ZERO, (List) List.of(listener));
        melodies.add(soundingMelody);
        int sounds = 0;
        var particlePositions = new java.util.HashSet<Vec3>();
        for (int tick = 0; tick <= 435; tick++) {
            var notes = new ArrayList<ExampleCallbacks.HornNote>();
            packetMelody.advance(tick, notes::add);
            for (var note : notes) {
                packets.clear();
                playHorn.invoke(callbacks, soundingMelody, note, (long) tick);
                assertInstanceOf(ClientboundSoundPacket.class, packets.get(packets.size() - 2));
                var particle = assertInstanceOf(ClientboundLevelParticlesPacket.class, packets.getLast());
                assertEquals(ParticleTypes.NOTE, particle.particle());
                assertEquals(0, particle.count());
                assertTrue(particle.x() >= -0.8D && particle.x() < 0.8D);
                assertTrue(particle.y() >= 0.8D && particle.y() < 2.1D);
                assertTrue(particle.z() >= -0.8D && particle.z() < 0.8D);
                particlePositions.add(new Vec3(particle.x(), particle.y(), particle.z()));
                assertEquals(1, packets.stream().filter(ClientboundLevelParticlesPacket.class::isInstance).count());
                sounds++;
            }
        }
        assertEquals(99, sounds, "Every actual note, including retriggers, sends exactly one particle immediately after its sound");
        assertTrue(particlePositions.size() > 1, "Particle positions must vary between notes");
        packets.clear();
        playHorn.invoke(callbacks, new ExampleCallbacks.TrumpetCanCan(0, Vec3.ZERO, (List) List.of(listener)), new ExampleCallbacks.HornNote(0, 55, 6, 0.65F), 400L);
        assertTrue(packets.isEmpty(), "A suppressed sound must not produce a particle");
        assertDoesNotThrow(() -> playHorn.invoke(callbacks, new ExampleCallbacks.TrumpetCanCan(0, Vec3.ZERO, List.of()), new ExampleCallbacks.HornNote(0, 127, 12, 0.65F), 400L));
        assertDoesNotThrow(() -> playHorn.invoke(callbacks, new ExampleCallbacks.TrumpetCanCan(0, Vec3.ZERO, List.of()), new ExampleCallbacks.HornNote(0, 0, 12, 0.65F), 400L));
        assertEquals(java.util.Set.of(ExampleCallbacks.BAT_CALLBACK_ID, ExampleCallbacks.IDLE_BUTTERFLY_CALLBACK_ID, ExampleCallbacks.TRUMPET_CAN_CAN_CALLBACK_ID), registeredCallbacks.keySet());
        assertTrue(callbacks.unregister());
        assertFalse(callbacks.unregister());
        assertTrue(melodies.contains(soundingMelody), "Unregistration preserves existing playback until onClose");
        var otherMelody = new ExampleCallbacks.TrumpetCanCan(0, Vec3.ZERO, List.of());
        melodies.add(otherMelody);
        var context = new TestContext();
        context.setUserState(soundingMelody);
        registeredCallbacks.get(ExampleCallbacks.TRUMPET_CAN_CAN_CALLBACK_ID).onClose(context);
        assertFalse(melodies.contains(soundingMelody));
        assertTrue(melodies.contains(otherMelody));
        assertInstanceOf(net.minecraft.network.protocol.game.ClientboundStopSoundPacket.class, packets.getLast());
        int count = packets.size();
        registeredCallbacks.get(ExampleCallbacks.TRUMPET_CAN_CAN_CALLBACK_ID).onClose(context);
        assertEquals(count, packets.size(), "A closed note must not send a second stop packet");
        context.setUserState(null);
        registeredCallbacks.get(ExampleCallbacks.TRUMPET_CAN_CAN_CALLBACK_ID).onClose(context);
        registeredCallbacks.get(ExampleCallbacks.BAT_CALLBACK_ID).onTick(context);
    }

    private static final class TestContext implements PlaybackContext {
        private Object state;
        public UUID sessionId() { return UUID.randomUUID(); }
        public String payload() { return ""; }
        public net.minecraft.server.MinecraftServer server() { throw new UnsupportedOperationException(); }
        public net.minecraft.server.level.ServerLevel level() { throw new UnsupportedOperationException(); }
        public long elapsedTicks() { return 0; }
        public int animationTick() { return 24; }
        public Optional<net.minecraft.world.entity.Entity> actor(String name) { throw new UnsupportedOperationException(); }
        public Optional<net.minecraft.world.entity.Entity> nodeEntity(String node) { throw new UnsupportedOperationException(); }
        public Vec3 nodeWorldPosition(String node) { throw new UnsupportedOperationException(); }
        public Vec3 rootPosition() { return Vec3.ZERO; }
        public Optional<PlaybackStopReason> stopReason() { return Optional.of(PlaybackStopReason.MANUAL); }
        public Object userState() { return state; }
        public void setUserState(Object state) { this.state = state; }
    }
}
