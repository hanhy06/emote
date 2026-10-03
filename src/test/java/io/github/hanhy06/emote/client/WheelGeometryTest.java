package io.github.hanhy06.emote.client;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class WheelGeometryTest {
    @Test
    void whiteSeparatorsKeepTheSameWidthFromCenterToOuterEdge() {
        WheelGeometry.WheelMetrics metrics = WheelGeometry.createMetrics(640, 480);
        int[] colors = new int[WheelGeometry.SLOT_COUNT];
        Arrays.fill(colors, 0x80252525);
        for (int scale : new int[] {1, 2, 4}) {
            BufferedImage image = WheelGeometry.createWheelImage(metrics, scale, colors, 0x70202020);
            int center = (metrics.outerRadius() + 1) * scale;
            int innerX = center + (metrics.centerRadius() + 8) * scale;
            int outerX = center + (metrics.outerRadius() - 8) * scale;
            int innerWidth = 0;
            int outerWidth = 0;
            for (int y = center - 3 * scale; y <= center + 3 * scale; y++) {
                int innerPixel = image.getRGB(innerX, y);
                int outerPixel = image.getRGB(outerX, y);
                assertTrue((innerPixel >>> 24) >= 0x80, "Separator must have a continuous disk underneath");
                assertTrue((outerPixel >>> 24) >= 0x80, "Separator must have a continuous disk underneath");
                if (((innerPixel >> 16) & 0xFF) > 60) {
                    innerWidth++;
                }
                if (((outerPixel >> 16) & 0xFF) > 60) {
                    outerWidth++;
                }
            }
            assertTrue(innerWidth > 0, "Separator must be a visible white line");
            assertEquals(innerWidth, outerWidth);
        }
    }

    @Test
    void textureHasTranslucentInteriorAndAntialiasedEdgesAtEachGuiScale() {
        WheelGeometry.WheelMetrics metrics = WheelGeometry.createMetrics(640, 480);
        int[] colors = new int[WheelGeometry.SLOT_COUNT];
        Arrays.fill(colors, 0x80252525);
        colors[0] = 0x990078D7;
        for (int scale : new int[] {1, 2, 4}) {
            BufferedImage image = WheelGeometry.createWheelImage(metrics, scale, colors, 0x70202020);
            int center = (metrics.outerRadius() + 1) * scale;
            assertEquals(center * 2, image.getWidth());
            assertEquals(0x70, image.getRGB(center, center) >>> 24);
            assertEquals(0, image.getRGB(0, 0) >>> 24);
            for (int slotIndex = 0; slotIndex < WheelGeometry.SLOT_COUNT; slotIndex++) {
                WheelGeometry.SlotGeometry slot = WheelGeometry.createSlot(slotIndex, metrics);
                int x = center + (slot.centerX() - metrics.centerX()) * scale;
                int y = center + (slot.centerY() - metrics.centerY()) * scale;
                assertEquals(colors[slotIndex] >>> 24, image.getRGB(x, y) >>> 24);
            }
            boolean hasPartialEdge = false;
            for (int y = 0; y < 3 * scale; y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int alpha = image.getRGB(x, y) >>> 24;
                    hasPartialEdge |= alpha > 0 && alpha < 0x99;
                }
            }
            assertTrue(hasPartialEdge, "Outer curve must contain partially covered pixels at GUI scale " + scale);
        }
    }

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
    void sectorsSelectTheirLabelsButExcludeCenterAndOutsideCircle() {
        WheelGeometry.WheelMetrics metrics = WheelGeometry.createMetrics(640, 480);
        for (int index = 0; index < WheelGeometry.SLOT_COUNT; index++) {
            WheelGeometry.SlotGeometry slot = WheelGeometry.createSlot(index, metrics);
            assertTrue(WheelGeometry.containsPoint(slot.xPoints(), slot.yPoints(), slot.centerX(), slot.centerY()));
            assertFalse(WheelGeometry.containsPoint(slot.xPoints(), slot.yPoints(), metrics.centerX(), metrics.centerY()));
            assertFalse(WheelGeometry.containsPoint(slot.xPoints(), slot.yPoints(), metrics.centerX(), metrics.centerY() - metrics.outerRadius() - 1));
            for (int other = 0; other < WheelGeometry.SLOT_COUNT; other++) {
                if (other != index) {
                    WheelGeometry.SlotGeometry otherSlot = WheelGeometry.createSlot(other, metrics);
                    assertFalse(WheelGeometry.containsPoint(otherSlot.xPoints(), otherSlot.yPoints(), slot.centerX(), slot.centerY()));
                }
            }
        }
    }

    @Test
    void wheelFitsAvailableSpaceOnCompactAndLargeScreens() {
        for (int[] size : new int[][] {{200, 160}, {320, 240}, {640, 480}, {1280, 720}}) {
            WheelGeometry.WheelMetrics metrics = WheelGeometry.createMetrics(size[0], size[1]);
            assertTrue(metrics.centerX() - metrics.outerRadius() >= 12);
            assertTrue(metrics.centerX() + metrics.outerRadius() <= size[0] - 12);
            assertTrue(metrics.centerY() - metrics.outerRadius() >= 32);
            assertTrue(metrics.centerY() + metrics.outerRadius() <= size[1] - 80);
            assertTrue(metrics.centerRadius() < metrics.ringRadius());
            assertTrue(metrics.ringRadius() < metrics.outerRadius());
        }
    }
}
