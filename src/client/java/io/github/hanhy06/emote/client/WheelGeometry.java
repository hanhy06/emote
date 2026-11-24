package io.github.hanhy06.emote.client;

import java.util.List;

final class WheelGeometry {
    static final int SLOT_COUNT = 5;
    static final int TITLE_BOTTOM = 44;
    static final int PAGE_TOP_FROM_BOTTOM = 44;
    static final double SLOT_ANGLE = 2 * Math.PI / SLOT_COUNT;
    static final float LABEL_RADIUS = 70.0F;

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

    private final long openedAt;
    private final float[] startWeights = new float[SLOT_COUNT];
    private long changedAt;
    private double startAngle;
    private double travelAngle;
    private float startOpacity;
    private int selected = -1;

    WheelGeometry(int width, int height, long now) {
        this(width / 2, TITLE_BOTTOM + (height - TITLE_BOTTOM - 52) / 2 - 3, radiusForScreen(width, height), now);
    }

    WheelGeometry(int centerX, int centerY, int outerRadius, long now) {
        this.centerX = centerX;
        this.centerY = centerY;
        this.outerRadius = outerRadius;
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

    int slotAt(double mouseX, double mouseY) {
        double x = mouseX - centerX;
        double y = mouseY - centerY;
        double radius = Math.hypot(x, y);
        if (radius < 41 * scale) {
            return -1;
        }
        int slot = Math.floorMod((int) Math.floor(Math.atan2(x, -y) / SLOT_ANGLE + 0.5), SLOT_COUNT);
        float hitRadius = outerRadius + (slot == selected ? lift : 0);
        return radius <= hitRadius ? slot : -1;
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
