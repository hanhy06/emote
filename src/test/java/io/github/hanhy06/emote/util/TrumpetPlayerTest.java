package io.github.hanhy06.emote.util;

import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class TrumpetPlayerTest {
    @Test
    void staticFunctionsSendCallerNotesAndStopWithoutSession() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var packets = new ArrayList<Packet<?>>();
        TrumpetPlayer.play(new Vec3(1, 2, 3), 60.5, 0.4F, 100, packets::add);
        assertEquals(1, packets.size());
        var sound = assertInstanceOf(ClientboundSoundPacket.class, packets.getFirst());
        assertEquals(TrumpetPlayer.SOUND, sound.getSound().value().location());
        assertEquals(SoundSource.RECORDS, sound.getSource());
        assertEquals(0.4F, sound.getVolume());
        assertEquals(1.0, sound.getX());
        assertEquals(2.0, sound.getY());
        assertEquals(3.0, sound.getZ());
        assertEquals(100, sound.getSeed());
        float expectedPitch = (float) (440.0 * Math.pow(2.0, (60.5 + 4.9 - 12.0 - 69.0) / 12.0) / 130.8);
        assertEquals(expectedPitch, sound.getPitch(), 0.0001F);
        TrumpetPlayer.stop(packets::add);
        var stop = assertInstanceOf(ClientboundStopSoundPacket.class, packets.getLast());
        assertEquals(TrumpetPlayer.SOUND, stop.getName());
        assertEquals(SoundSource.RECORDS, stop.getSource());

        packets.clear();
        assertThrows(IllegalArgumentException.class, () -> TrumpetPlayer.play(Vec3.ZERO, Double.NaN, 0.4F, 100, packets::add));
        assertThrows(IllegalArgumentException.class, () -> TrumpetPlayer.play(Vec3.ZERO, 60, 0, 100, packets::add));
        assertTrue(packets.isEmpty());
    }
}
