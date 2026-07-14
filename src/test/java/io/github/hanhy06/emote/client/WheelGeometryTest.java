package io.github.hanhy06.emote.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WheelGeometryTest {
    @Test
    void slotsArePlacedClockwiseFromTop() {
        WheelGeometry.WheelMetrics metrics = WheelGeometry.createMetrics(640, 480);

        WheelGeometry.SlotGeometry top = WheelGeometry.createSlot(0, metrics);
        WheelGeometry.SlotGeometry rightBottom = WheelGeometry.createSlot(2, metrics);
        WheelGeometry.SlotGeometry bottom = WheelGeometry.createSlot(3, metrics);

        assertEquals(metrics.centerX(), top.centerX());
        assertEquals(metrics.centerY() - metrics.ringRadius(), top.centerY());
        assertTrue(rightBottom.centerX() > metrics.centerX());
        assertTrue(rightBottom.centerY() > metrics.centerY());
        assertEquals(metrics.centerX(), bottom.centerX());
        assertEquals(metrics.centerY() + metrics.ringRadius(), bottom.centerY());
    }

    @Test
    void hexagonHitTestSeparatesInsideAndOutsidePoints() {
        int[] xPoints = WheelGeometry.createHexagonXPoints(100, 30);
        int[] yPoints = WheelGeometry.createHexagonYPoints(100, 30);

        assertTrue(WheelGeometry.containsPoint(xPoints, yPoints, 100, 100));
        assertFalse(WheelGeometry.containsPoint(xPoints, yPoints, 131, 100));
    }

    @Test
    void metricsKeepMinimumSlotAndRingSizesOnSmallScreens() {
        WheelGeometry.WheelMetrics metrics = WheelGeometry.createMetrics(200, 160);

        assertEquals(28, metrics.slotRadius());
        assertEquals(56, metrics.ringRadius());
        assertEquals(20, metrics.centerRadius());
    }
}
