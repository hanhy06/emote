package io.github.hanhy06.emote.client;

import java.awt.*;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;

final class WheelGeometry {
    static final int SLOT_COUNT = 6;
    private static final int ARC_SEGMENTS = 24;
    private static final double SLOT_ANGLE = Math.PI * 2.0D / SLOT_COUNT;

    private WheelGeometry() {
    }

    static WheelMetrics createMetrics(int width, int height) {
        int centerX = width / 2;
        int centerY = Math.max(32, height / 2 - 18);
        int outerRadius = Math.max(16, Math.min(164, Math.min(width / 2 - 12, Math.min(centerY - 32, height - centerY - 80))));
        int centerRadius = Math.max(8, outerRadius / 3);
        int ringRadius = (centerRadius + outerRadius) / 2;
        int textWidth = Math.max(8, outerRadius - centerRadius - 12);
        return new WheelMetrics(centerX, centerY, outerRadius, ringRadius, centerRadius, textWidth, Math.max(16, Math.min(280, width - 48)));
    }

    static SlotGeometry createSlot(int slotIndex, WheelMetrics metrics) {
        if (slotIndex < 0 || slotIndex >= SLOT_COUNT) {
            throw new IllegalArgumentException("slot index must be between 0 and " + (SLOT_COUNT - 1));
        }
        double angle = -Math.PI / 2.0D + slotIndex * SLOT_ANGLE;
        double startAngle = angle - SLOT_ANGLE / 2.0D;
        double endAngle = angle + SLOT_ANGLE / 2.0D;
        int[] xPoints = new int[(ARC_SEGMENTS + 1) * 2];
        int[] yPoints = new int[xPoints.length];
        for (int index = 0; index <= ARC_SEGMENTS; index++) {
            double outerAngle = startAngle + (endAngle - startAngle) * index / ARC_SEGMENTS;
            double innerAngle = endAngle - (endAngle - startAngle) * index / ARC_SEGMENTS;
            xPoints[index] = metrics.centerX() + (int) Math.round(Math.cos(outerAngle) * metrics.outerRadius());
            yPoints[index] = metrics.centerY() + (int) Math.round(Math.sin(outerAngle) * metrics.outerRadius());
            xPoints[ARC_SEGMENTS + 1 + index] = metrics.centerX() + (int) Math.round(Math.cos(innerAngle) * metrics.centerRadius());
            yPoints[ARC_SEGMENTS + 1 + index] = metrics.centerY() + (int) Math.round(Math.sin(innerAngle) * metrics.centerRadius());
        }
        int centerX = metrics.centerX() + (int) Math.round(Math.cos(angle) * metrics.ringRadius());
        int centerY = metrics.centerY() + (int) Math.round(Math.sin(angle) * metrics.ringRadius());
        return new SlotGeometry(
            centerX,
            centerY,
            xPoints,
            yPoints,
            metrics.textWidth()
        );
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

    static BufferedImage createWheelImage(WheelMetrics metrics, int scale, int[] slotColors, int centerColor) {
        int radius = metrics.outerRadius();
        int size = (radius + 1) * 2;
        BufferedImage image = new BufferedImage(size * scale, size * scale, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.scale(scale, scale);
            double center = radius + 1;
            // Fill a continuous disk first; separators are strokes, not angular gaps.
            int baseColor = slotColors[0];
            int highestCount = 0;
            for (int color : slotColors) {
                int count = 0;
                for (int other : slotColors) {
                    if (color == other) {
                        count++;
                    }
                }
                if (count > highestCount) {
                    baseColor = color;
                    highestCount = count;
                }
            }
            graphics.setColor(new Color(baseColor, true));
            graphics.fill(new Ellipse2D.Double(center - radius, center - radius, radius * 2, radius * 2));
            for (int slotIndex = 0; slotIndex < SLOT_COUNT; slotIndex++) {
                if (slotColors[slotIndex] == baseColor) {
                    continue;
                }
                double angle = -Math.PI / 2.0D + slotIndex * SLOT_ANGLE;
                double startAngle = -Math.toDegrees(angle - SLOT_ANGLE / 2.0D);
                graphics.setColor(new Color(slotColors[slotIndex], true));
                graphics.fill(new Arc2D.Double(center - radius, center - radius, radius * 2, radius * 2, startAngle, -Math.toDegrees(SLOT_ANGLE), Arc2D.PIE));
            }
            int centerRadius = metrics.centerRadius();
            Ellipse2D.Double centerCircle = new Ellipse2D.Double(center - centerRadius, center - centerRadius, centerRadius * 2, centerRadius * 2);
            graphics.setColor(new Color(centerColor, true));
            graphics.fill(centerCircle);
            graphics.setComposite(AlphaComposite.SrcOver);
            graphics.setColor(new Color(0xB0FFFFFF, true));
            graphics.setStroke(new BasicStroke(1.0F, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
            for (int index = 0; index < SLOT_COUNT; index++) {
                double angle = -Math.PI / 2.0D - SLOT_ANGLE / 2.0D + index * SLOT_ANGLE;
                double cos = Math.cos(angle);
                double sin = Math.sin(angle);
                graphics.draw(new Line2D.Double(center + cos * centerRadius, center + sin * centerRadius, center + cos * radius, center + sin * radius));
            }
            graphics.draw(centerCircle);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    record WheelMetrics(
        int centerX,
        int centerY,
        int outerRadius,
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
