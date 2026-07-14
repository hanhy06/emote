package io.github.hanhy06.emote.client;

final class WheelGeometry {
    static final int SLOT_COUNT = 6;

    private WheelGeometry() {
    }

    static WheelMetrics createMetrics(int width, int height) {
        int centerX = width / 2;
        int centerY = height / 2 - 18;
        int slotRadius = Math.clamp(Math.min(width, height) / 12, 28, 44);
        int ringRadiusLimitX = Math.max(slotRadius * 2, width / 2 - slotRadius - 18);
        int ringRadiusLimitY = Math.max(slotRadius * 2, Math.min(centerY - slotRadius - 18, height - centerY - slotRadius - 90));
        int ringRadius = Math.max(slotRadius * 2, Math.min(slotRadius * 3 + 20, Math.min(ringRadiusLimitX, ringRadiusLimitY)));
        int centerRadius = Math.max(20, slotRadius - 12);
        int descriptionWidth = Math.min(280, width - 48);
        return new WheelMetrics(centerX, centerY, slotRadius, ringRadius, centerRadius, Math.max(52, slotRadius + 24), descriptionWidth);
    }

    static SlotGeometry createSlot(int slotIndex, WheelMetrics metrics) {
        if (slotIndex < 0 || slotIndex >= SLOT_COUNT) {
            throw new IllegalArgumentException("slot index must be between 0 and " + (SLOT_COUNT - 1));
        }
        double angle = -Math.PI / 2.0D + slotIndex * (Math.PI * 2.0D / SLOT_COUNT);
        int centerX = metrics.centerX() + (int) Math.round(Math.cos(angle) * metrics.ringRadius());
        int centerY = metrics.centerY() + (int) Math.round(Math.sin(angle) * metrics.ringRadius());
        return new SlotGeometry(
                centerX,
                centerY,
                createHexagonXPoints(centerX, metrics.slotRadius()),
                createHexagonYPoints(centerY, metrics.slotRadius()),
                metrics.textWidth()
        );
    }

    static int[] createHexagonXPoints(int centerX, int radius) {
        int[] points = new int[SLOT_COUNT];
        for (int index = 0; index < points.length; index++) {
            double angle = Math.PI * 2.0D * index / points.length;
            points[index] = centerX + (int) Math.round(Math.cos(angle) * radius);
        }
        return points;
    }

    static int[] createHexagonYPoints(int centerY, int radius) {
        int[] points = new int[SLOT_COUNT];
        for (int index = 0; index < points.length; index++) {
            double angle = Math.PI * 2.0D * index / points.length;
            points[index] = centerY + (int) Math.round(Math.sin(angle) * radius);
        }
        return points;
    }

    static boolean containsPoint(int[] xPoints, int[] yPoints, double pointX, double pointY) {
        boolean inside = false;
        for (int currentIndex = 0, previousIndex = xPoints.length - 1; currentIndex < xPoints.length; previousIndex = currentIndex++) {
            boolean intersects = (yPoints[currentIndex] > pointY) != (yPoints[previousIndex] > pointY)
                    && pointX < (double) (xPoints[previousIndex] - xPoints[currentIndex]) * (pointY - yPoints[currentIndex])
                    / (double) (yPoints[previousIndex] - yPoints[currentIndex]) + xPoints[currentIndex];
            if (intersects) {
                inside = !inside;
            }
        }
        return inside;
    }

    static int average(int[] values) {
        int sum = 0;
        for (int value : values) {
            sum += value;
        }
        return sum / values.length;
    }

    static int estimateRadius(int[] values, int center) {
        int total = 0;
        for (int value : values) {
            total += Math.abs(value - center);
        }
        return Math.max(1, total / values.length);
    }

    record WheelMetrics(
            int centerX,
            int centerY,
            int slotRadius,
            int ringRadius,
            int centerRadius,
            int textWidth,
            int descriptionWidth
    ) {
    }

    record SlotGeometry(
            int centerX,
            int centerY,
            int[] xPoints,
            int[] yPoints,
            int textWidth
    ) {
    }
}
