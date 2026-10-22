package io.github.hanhy06.emote.examples;

import io.github.hanhy06.emote.api.EmoteCallbacks;
import io.github.hanhy06.emote.api.PlaybackContext;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Plays a timed melody using pitched goat-horn sound packets.
 * Each playback has its own melody state; the callback instance tracks shared sound usage.
 */
public final class TrumpetExample implements EmoteCallbacks {
    private static final int MAX_HORN_DURATION_TICKS = 7;
    private static final float RANGE = 24.0F;
    public static final Identifier SOUND = Identifier.parse("minecraft:item.goat_horn.sound.0");
    private static final double BASE_FREQUENCY = 130.8D;
    private static final double PITCH_OFFSET = 4.9D;

    private static final List<HornNote> TRUMPET_CAN_CAN_NOTES = List.of(
        new HornNote(28, 55, 6, 0.65F),
        new HornNote(34, 55, 6, 0.65F),
        new HornNote(40, 57, 3, 0.65F),
        new HornNote(43, 60, 3, 0.65F),
        new HornNote(46, 59, 3, 0.65F),
        new HornNote(49, 57, 3, 0.65F),
        new HornNote(52, 62, 6, 0.65F),
        new HornNote(58, 62, 6, 0.65F),
        new HornNote(64, 62, 3, 0.65F),
        new HornNote(67, 64, 3, 0.65F),
        new HornNote(70, 59, 3, 0.65F),
        new HornNote(73, 60, 3, 0.65F),
        new HornNote(76, 57, 6, 0.65F),
        new HornNote(82, 57, 6, 0.65F),
        new HornNote(88, 57, 3, 0.65F),
        new HornNote(91, 60, 3, 0.65F),
        new HornNote(94, 59, 3, 0.65F),
        new HornNote(97, 57, 3, 0.65F),
        new HornNote(100, 55, 3, 0.65F),
        new HornNote(103, 67, 3, 0.65F),
        new HornNote(106, 66, 3, 0.65F),
        new HornNote(109, 64, 3, 0.65F),
        new HornNote(112, 62, 3, 0.65F),
        new HornNote(115, 60, 3, 0.65F),
        new HornNote(118, 59, 3, 0.65F),
        new HornNote(121, 57, 3, 0.65F),
        new HornNote(124, 55, 6, 0.65F),
        new HornNote(130, 55, 6, 0.65F),
        new HornNote(136, 57, 3, 0.65F),
        new HornNote(139, 60, 3, 0.65F),
        new HornNote(142, 59, 3, 0.65F),
        new HornNote(145, 57, 3, 0.65F),
        new HornNote(148, 62, 6, 0.65F),
        new HornNote(154, 62, 6, 0.65F),
        new HornNote(160, 62, 3, 0.65F),
        new HornNote(163, 64, 3, 0.65F),
        new HornNote(166, 59, 3, 0.65F),
        new HornNote(169, 60, 3, 0.65F),
        new HornNote(172, 57, 6, 0.65F),
        new HornNote(178, 57, 6, 0.65F),
        new HornNote(184, 57, 3, 0.65F),
        new HornNote(187, 60, 3, 0.65F),
        new HornNote(190, 59, 3, 0.65F),
        new HornNote(193, 57, 3, 0.65F),
        new HornNote(196, 55, 3, 0.65F),
        new HornNote(199, 62, 3, 0.65F),
        new HornNote(202, 57, 3, 0.65F),
        new HornNote(205, 59, 3, 0.65F),
        new HornNote(208, 55, 6, 0.65F),
        new HornNote(214, 50, 6, 0.65F),
        new HornNote(220, 55, 6, 0.65F),
        new HornNote(226, 55, 6, 0.65F),
        new HornNote(232, 57, 3, 0.65F),
        new HornNote(235, 60, 3, 0.65F),
        new HornNote(238, 59, 3, 0.65F),
        new HornNote(241, 57, 3, 0.65F),
        new HornNote(244, 62, 6, 0.65F),
        new HornNote(250, 62, 6, 0.65F),
        new HornNote(256, 62, 3, 0.65F),
        new HornNote(259, 64, 3, 0.65F),
        new HornNote(262, 59, 3, 0.65F),
        new HornNote(265, 60, 3, 0.65F),
        new HornNote(268, 57, 6, 0.65F),
        new HornNote(274, 57, 6, 0.65F),
        new HornNote(280, 57, 3, 0.65F),
        new HornNote(283, 60, 3, 0.65F),
        new HornNote(286, 59, 3, 0.65F),
        new HornNote(289, 57, 3, 0.65F),
        new HornNote(292, 55, 3, 0.65F),
        new HornNote(295, 67, 3, 0.65F),
        new HornNote(298, 66, 3, 0.65F),
        new HornNote(301, 64, 3, 0.65F),
        new HornNote(304, 62, 3, 0.65F),
        new HornNote(307, 60, 3, 0.65F),
        new HornNote(310, 59, 3, 0.65F),
        new HornNote(313, 57, 3, 0.65F),
        new HornNote(316, 55, 6, 0.65F),
        new HornNote(322, 55, 6, 0.65F),
        new HornNote(328, 57, 3, 0.65F),
        new HornNote(331, 60, 3, 0.65F),
        new HornNote(334, 59, 3, 0.65F),
        new HornNote(337, 57, 3, 0.65F),
        new HornNote(340, 62, 6, 0.65F),
        new HornNote(346, 62, 6, 0.65F),
        new HornNote(352, 62, 3, 0.65F),
        new HornNote(355, 64, 3, 0.65F),
        new HornNote(358, 59, 3, 0.65F),
        new HornNote(361, 60, 3, 0.65F),
        new HornNote(364, 57, 6, 0.65F),
        new HornNote(370, 57, 6, 0.65F),
        new HornNote(376, 57, 3, 0.65F),
        new HornNote(379, 60, 3, 0.65F),
        new HornNote(382, 59, 3, 0.65F),
        new HornNote(385, 57, 3, 0.65F),
        new HornNote(388, 55, 3, 0.65F),
        new HornNote(391, 62, 3, 0.65F),
        new HornNote(394, 57, 3, 0.65F),
        new HornNote(397, 59, 3, 0.65F),
        new HornNote(400, 55, 6, 0.65F)
    );

    private final Set<TrumpetCanCan> activeMelodies = new HashSet<>();

    @Override
    public void onStart(PlaybackContext context) {
        Vec3 origin = context.getRootPosition();

        // Capture the listeners when playback starts so the melody has a stable audience.
        List<HornListener> listeners = context.getWorld().players().stream()
            .filter(player -> player.position().distanceToSqr(origin) <= RANGE * RANGE)
            .map(player -> new HornListener(player.getUUID(), player.connection::send))
            .toList();
        TrumpetCanCan melody = new TrumpetCanCan(0, origin, listeners);
        context.setUserState(melody);
        activeMelodies.add(melody);
    }

    @Override
    public void onTick(PlaybackContext context) {
        TrumpetCanCan melody = (TrumpetCanCan) context.getUserState();
        long serverTick = context.getServer().getTickCount();

        // Server ticks determine when to stop a sound; elapsed playback ticks select notes.
        if (melody.activeNote != null && melody.activeNote.endTick() <= serverTick) {
            melody.stopNote();
        }

        melody.advance(context.getElapsedTicks(), note -> playHorn(melody, note, serverTick));
    }

    @Override
    public void onClose(PlaybackContext context) {
        if (context.getUserState() instanceof TrumpetCanCan melody) {
            activeMelodies.remove(melody);
            melody.stopNote();
        }
    }

    private void playHorn(TrumpetCanCan melody, HornNote note, long serverTick) {
        melody.stopNote();
        for (TrumpetCanCan active : this.activeMelodies) {
            if (active.activeNote != null && active.activeNote.endTick() <= serverTick) {
                active.stopNote();
            }
        }
        Vec3 origin = melody.origin;
        var random = ThreadLocalRandom.current();
        var particle = new ClientboundLevelParticlesPacket(
            ParticleTypes.NOTE, false, false,
            origin.x + random.nextDouble(-0.8D, 0.8D),
            origin.y + random.nextDouble(0.8D, 2.1D),
            origin.z + random.nextDouble(-0.8D, 0.8D),
            (float) ((note.midi() % 12) / 12.0), 0.0F, 0.0F, 1.0F, 0
        );
        List<HornListener> listeners = new ArrayList<>();

        for (HornListener listener : melody.listeners) {
            // A stop-sound packet targets the sound ID, so avoid competing notes per listener.
            boolean soundOccupied = this.activeMelodies.stream()
                .map(active -> active.activeNote)
                .filter(active -> active != null && active.endTick() > serverTick)
                .flatMap(active -> active.listeners().stream())
                .anyMatch(activeListener -> activeListener.id().equals(listener.id()));

            if (soundOccupied) {
                continue;
            }

            playNote(melody.origin, note.midi(), note.volume(), serverTick, listener.send());
            listener.send().accept(particle);
            listeners.add(listener);
        }
        if (!listeners.isEmpty()) {
            long endTick = serverTick + Math.min(note.durationTicks(), MAX_HORN_DURATION_TICKS);
            melody.activeNote = new ActiveHornNote(endTick, List.copyOf(listeners));
        }
    }

    /** The offset is measured from playback start, rather than from the previous note. */
    record HornNote(int offsetTicks, double midi, int durationTicks, float volume) {
    }

    static final class TrumpetCanCan {
        private final long startTick;
        private final Vec3 origin;
        private final List<HornListener> listeners;
        private int nextNote;
        private ActiveHornNote activeNote;

        TrumpetCanCan(long startTick, Vec3 origin, List<HornListener> listeners) {
            this.startTick = startTick;
            this.origin = origin;
            this.listeners = listeners;
        }

        void stopNote() {
            ActiveHornNote note = this.activeNote;
            this.activeNote = null;
            if (note != null) {
                for (HornListener listener : note.listeners()) {
                    stopSound(listener.send());
                }
            }
        }

        void advance(long elapsedTicks, Consumer<HornNote> play) {
            while (this.nextNote < TRUMPET_CAN_CAN_NOTES.size()) {
                HornNote note = TRUMPET_CAN_CAN_NOTES.get(this.nextNote);
                if (elapsedTicks - this.startTick < note.offsetTicks()) {
                    return;
                }

                this.nextNote++;
                play.accept(note);
            }
        }
    }

    private record HornListener(UUID id, Consumer<Packet<?>> send) {
    }

    private record ActiveHornNote(long endTick, List<HornListener> listeners) {
    }

    private static void playNote(Vec3 origin, double midi, float volume, long seed, Consumer<Packet<?>> send) {
        if (!Double.isFinite(midi) || midi < 0 || midi > 127) {
            throw new IllegalArgumentException("MIDI note must be between 0 and 127");
        }

        if (!Float.isFinite(volume) || volume <= 0 || volume > 1) {
            throw new IllegalArgumentException("Horn volume must be greater than 0 and at most 1");
        }

        // Convert a MIDI note to a pitch multiplier for the chosen sound sample.
        double frequency = 440.0 * Math.pow(2.0, (midi + PITCH_OFFSET - 12.0 - 69.0) / 12.0);
        float pitch = Math.clamp((float) (frequency / BASE_FREQUENCY), 0.5F, 2.0F);
        send.accept(new ClientboundSoundPacket(
            Holder.direct(SoundEvent.createFixedRangeEvent(SOUND, RANGE)), SoundSource.RECORDS,
            origin.x, origin.y, origin.z, volume, pitch, seed
        ));
    }

    private static void stopSound(Consumer<Packet<?>> send) {
        send.accept(new ClientboundStopSoundPacket(SOUND, SoundSource.RECORDS));
    }
}
