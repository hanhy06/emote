package io.github.hanhy06.emote.util;

import net.minecraft.core.Holder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.function.Consumer;

public final class TrumpetPlayer {
    public static final float RANGE = 24.0F;
    public static final Identifier SOUND = Identifier.parse("minecraft:item.goat_horn.sound.0");
    private static final double BASE_FREQUENCY = 130.8D;
    private static final double PITCH_OFFSET = 4.9D;

    public static void play(Vec3 origin, double midi, float volume, long seed, Consumer<Packet<?>> send) {
        if (!Double.isFinite(midi) || midi < 0 || midi > 127) throw new IllegalArgumentException("MIDI note must be between 0 and 127");
        if (!Float.isFinite(volume) || volume <= 0 || volume > 1) throw new IllegalArgumentException("Horn volume must be greater than 0 and at most 1");

        double frequency = 440.0 * Math.pow(2.0, (midi + PITCH_OFFSET - 12.0 - 69.0) / 12.0);
        float pitch = Mth.clamp((float) (frequency / BASE_FREQUENCY), 0.5F, 2.0F);
        send.accept(new ClientboundSoundPacket(
            Holder.direct(SoundEvent.createFixedRangeEvent(SOUND, RANGE)), SoundSource.RECORDS,
            origin.x, origin.y, origin.z, volume, pitch, seed
        ));
    }

    public static void stop(Consumer<Packet<?>> send) {
        send.accept(new ClientboundStopSoundPacket(SOUND, SoundSource.RECORDS));
    }
}
