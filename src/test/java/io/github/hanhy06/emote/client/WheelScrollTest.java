package io.github.hanhy06.emote.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WheelScrollTest {
    @Test
    void fiveScrollUnitsMoveOnePageAndScrollingContinues() {
        WheelScreen.ScrollAccumulator scroll = new WheelScreen.ScrollAccumulator();
        for (int page = 0; page < 3; page++) {
            for (int input = 0; input < 4; input++) {
                assertEquals(0, scroll.add(1.0D));
            }
            assertEquals(1, scroll.add(1.0D));
        }
    }

    @Test
    void fractionalTouchpadInputIsAccumulated() {
        WheelScreen.ScrollAccumulator scroll = new WheelScreen.ScrollAccumulator();
        for (int input = 0; input < 49; input++) {
            assertEquals(0, scroll.add(0.1D));
        }
        assertEquals(1, scroll.add(0.1D));
        assertEquals(2, scroll.add(12.0D));
        assertEquals(1, scroll.add(3.0D));
    }

    @Test
    void reversingDirectionDiscardsPreviousPartialInput() {
        WheelScreen.ScrollAccumulator scroll = new WheelScreen.ScrollAccumulator();
        assertEquals(0, scroll.add(4.0D));
        assertEquals(0, scroll.add(-1.0D));
        assertEquals(-1, scroll.add(-4.0D));
        assertEquals(1, scroll.add(5.0D));
    }

    @Test
    void emptyOrInvalidInputDoesNotChangeAccumulation() {
        WheelScreen.ScrollAccumulator scroll = new WheelScreen.ScrollAccumulator();
        assertEquals(0, scroll.add(3.0D));
        assertEquals(0, scroll.add(0.0D));
        assertEquals(0, scroll.add(Double.NaN));
        assertEquals(0, scroll.add(Double.POSITIVE_INFINITY));
        assertEquals(1, scroll.add(2.0D));
    }
}
