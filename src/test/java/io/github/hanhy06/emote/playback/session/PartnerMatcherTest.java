package io.github.hanhy06.emote.playback.session;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartnerMatcherTest {
    @Test
    void acceptsTwoNearbyPlayersFacingEachOther() {
        assertTrue(PartnerMatcher.matchesGeometry(
            Vec3.ZERO,
            0.0F,
            new Vec3(0.0D, 0.0D, 1.2D),
            180.0F
        ));
    }

    @Test
    void rejectsDistanceHeightAndFacingOutsideFixedLimits() {
        assertFalse(PartnerMatcher.matchesGeometry(Vec3.ZERO, 0.0F, new Vec3(0.0D, 0.0D, 2.01D), 180.0F));
        assertFalse(PartnerMatcher.matchesGeometry(Vec3.ZERO, 0.0F, new Vec3(0.0D, 1.01D, 1.2D), 180.0F));
        assertFalse(PartnerMatcher.matchesGeometry(Vec3.ZERO, 0.0F, new Vec3(0.0D, 0.0D, 1.2D), 90.0F));
        assertFalse(PartnerMatcher.matchesGeometry(Vec3.ZERO, 90.0F, new Vec3(0.0D, 0.0D, 1.2D), 180.0F));
    }

    @Test
    void acceptsFacingAtExactlyFortyFiveDegrees() {
        assertTrue(PartnerMatcher.matchesGeometry(
            Vec3.ZERO,
            45.0F,
            new Vec3(-1.0D, 0.0D, 1.0D),
            -135.0F
        ));
    }
}
