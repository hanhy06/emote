package io.github.hanhy06.emote.client;

import org.jspecify.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.util.List;

final class WheelGeometry {
    static final int SLOT_COUNT = 5;
    static final int TITLE_BOTTOM = 44;
    static final int PAGE_TOP_FROM_BOTTOM = 44;
    static final double SLOT_ANGLE = 2 * Math.PI / SLOT_COUNT;
    static final float LABEL_RADIUS = 70.0F;
    static final int SLOT_X = -64;
    static final int SLOT_Y = -106;
    static final int SLOT_WIDTH = 128;
    static final int SLOT_HEIGHT = 80;

    private static final int OPEN_DURATION_MS = 155;
    private static final int OPEN_STAGGER_MS = 12;
    private static final long SELECTION_DURATION_NS = 145_000_000L;
    private static final long INDICATOR_DURATION_NS = 205_000_000L;

    final int centerX;
    final int centerY;
    final int outerRadius;
    final float scale;
    final int textWidth;
    final float lift;

    private final @Nullable BufferedImage slotMask;
    private final long openedAt;
    private final float[] startWeights = new float[SLOT_COUNT];
    private long changedAt;
    private double startAngle;
    private double travelAngle;
    private float startOpacity;
    private int selected = -1;

    WheelGeometry(int width, int height, long now, @Nullable BufferedImage slotMask) {
        this(width / 2, TITLE_BOTTOM + (height - TITLE_BOTTOM - 52) / 2 - 3, radiusForScreen(width, height), now, slotMask);
    }

    WheelGeometry(int centerX, int centerY, int outerRadius, long now, @Nullable BufferedImage slotMask) {
        this.centerX = centerX;
        this.centerY = centerY;
        this.outerRadius = outerRadius;
        this.slotMask = slotMask;
        scale = outerRadius / 100.0F;
        lift = Math.min(4, outerRadius * 0.045F);
        textWidth = Math.max(8, (int) (outerRadius * 0.61F));
        openedAt = now;
        changedAt = now;
    }

    private static int radiusForScreen(int width, int height) {
        int wheelHeight = height - TITLE_BOTTOM - 52;
        int limit = Math.min(width / 2 - 8, wheelHeight / 2);
        return Math.max(12, Math.min(Math.min(width, height) * 9 / 25, limit - 4));
    }

    static <T> List<T> page(List<T> slots, int pageIndex) {
        int start = Math.min(slots.size(), pageIndex * SLOT_COUNT);
        return slots.subList(start, start + Math.min(SLOT_COUNT, slots.size() - start));
    }

    int selected() {
        return selected;
    }

    int hoveredSlotAt(double mouseX, double mouseY, long now) {
        // Maintain hover as the selected slot lifts away from its initial position.
        if (selected >= 0 && containsSlot(selected, mouseX, mouseY, now, 0)) {
            return selected;
        }
        return slotAt(mouseX, mouseY, now);
    }

    int slotAt(double mouseX, double mouseY, long now) {
        float progress = selectionProgress(now);
        for (int slot = SLOT_COUNT - 1; slot >= 0; slot--) {
            if (containsSlot(slot, mouseX, mouseY, now, slotLift(weight(slot, progress)))) {
                return slot;
            }
        }
        return -1;
    }

    private boolean containsSlot(int slot, double mouseX, double mouseY, long now, float lift) {
        float entrance = entrance(slot, now);
        if (slotMask == null || entrance <= 0) return false;

        double angle = slot * SLOT_ANGLE;
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double dx = mouseX - centerX;
        double dy = mouseY - centerY;
        float scale = slotScale(entrance);
        double x = (dx * cos + dy * sin) / scale - SLOT_X;
        double y = (dy * cos - dx * sin + lift) / scale - SLOT_Y;
        if (x < 0 || x >= SLOT_WIDTH || y < 0 || y >= SLOT_HEIGHT) return false;

        // The shader uses the bilinearly sampled red channel as a signed distance.
        int width = slotMask.getWidth();
        int height = slotMask.getHeight();
        double sampleX = x * width / SLOT_WIDTH - 0.5;
        double sampleY = y * height / SLOT_HEIGHT - 0.5;
        int left = (int) Math.floor(sampleX);
        int top = (int) Math.floor(sampleY);
        double fractionX = sampleX - left;
        double fractionY = sampleY - top;
        int right = Math.min(left + 1, width - 1);
        int bottom = Math.min(top + 1, height - 1);
        left = Math.max(left, 0);
        top = Math.max(top, 0);
        double upper = red(slotMask, left, top) * (1 - fractionX) + red(slotMask, right, top) * fractionX;
        double lower = red(slotMask, left, bottom) * (1 - fractionX) + red(slotMask, right, bottom) * fractionX;
        return upper * (1 - fractionY) + lower * fractionY >= 127.5;
    }

    private static int red(BufferedImage mask, int x, int y) {
        return (mask.getRGB(x, y) >>> 16) & 0xFF;
    }

    float slotScale(float entrance) {
        return scale * (0.93F + 0.07F * entrance);
    }

    float slotLift(float weight) {
        return lift * weight;
    }

    boolean select(int next, long now) {
        if (next == selected) {
            return false;
        }
        float progress = selectionProgress(now);
        for (int i = 0; i < SLOT_COUNT; i++) {
            startWeights[i] = weight(i, progress);
        }
        startOpacity = indicatorOpacity(progress);
        startAngle = indicatorAngle(now);
        if (next >= 0 && startOpacity == 0) {
            startAngle = next * SLOT_ANGLE;
        }
        travelAngle = next < 0 ? 0 : Math.IEEEremainder(next * SLOT_ANGLE - startAngle, 2 * Math.PI);
        selected = next;
        changedAt = now;
        return true;
    }

    float entrance(int slot, long now) {
        float elapsedMs = (now - openedAt) / 1_000_000.0F - slot * OPEN_STAGGER_MS;
        return easeOutCubic(elapsedMs / OPEN_DURATION_MS);
    }

    float selectionProgress(long now) {
        return easeOutCubic((float) (now - changedAt) / SELECTION_DURATION_NS);
    }

    private static float easeOutCubic(float progress) {
        float remaining = 1 - Math.clamp(progress, 0, 1);
        return 1 - remaining * remaining * remaining;
    }

    float weight(int slot, float progress) {
        return startWeights[slot] * (1 - progress) + (slot == selected ? progress : 0);
    }

    float indicatorOpacity(float progress) {
        return startOpacity * (1 - progress) + (selected >= 0 ? progress : 0);
    }

    double indicatorAngle(long now) {
        // A small overshoot lets the marker settle on the notch, even after a quick reversal.
        float remaining = 1 - Math.clamp((float) (now - changedAt) / INDICATOR_DURATION_NS, 0, 1);
        double eased = 1 + remaining * remaining * (0.8 - 1.8 * remaining);
        return startAngle + travelAngle * eased;
    }
}
