package io.github.hanhy06.emote.client;

import io.github.hanhy06.emote.client.WheelGeometry.SlotGeometry;
import io.github.hanhy06.emote.client.WheelGeometry.WheelMetrics;
import io.github.hanhy06.emote.emote.PlayableEmote;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Environment(EnvType.CLIENT)
public class WheelScreen extends Screen {
    private static final int EDIT_BUTTON_WIDTH = 50;
    private static final int LEFT_MOUSE_BUTTON = 0;
    private static final int RIGHT_MOUSE_BUTTON = 1;
    private static final int BACKGROUND_TOP_COLOR = 0x7A101A22;
    private static final int BACKGROUND_BOTTOM_COLOR = 0xAD091117;
    private static final int SLOT_BORDER_COLOR = 0xFFA9C7D8;
    private static final int SLOT_FILL_COLOR = 0xD0223240;
    private static final int SLOT_HIGHLIGHT_FILL_COLOR = 0xF0517A94;
    private static final int SLOT_EMPTY_FILL_COLOR = 0x7F1A2530;
    private static final int CENTER_BORDER_COLOR = 0xFFDFE7EE;
    private static final int CENTER_FILL_COLOR = 0xD018242F;
    private static final int TITLE_COLOR = 0xFFF7FAFC;
    private static final int BODY_COLOR = 0xFFD1D9DF;
    private static final int MUTED_COLOR = 0xFF9DB0BC;

    private final WheelController controller;
    private final List<PlayableEmote> emotes;
    private final KeyMapping keyMapping;
    private final Component bindingLabel;

    private WheelMetrics metrics;
    private List<SlotGeometry> slotGeometries = List.of();
    private int[] centerXPoints = new int[0];
    private int[] centerYPoints = new int[0];
    private int pageIndex;
    private int hoveredSlotIndex = -1;

    public WheelScreen(WheelController controller, List<PlayableEmote> emotes, int pageIndex, KeyMapping keyMapping) {
        super(Component.translatable("screen.emote.wheel.title"));
        this.controller = controller;
        this.emotes = List.copyOf(emotes);
        this.pageIndex = Math.clamp(pageIndex, 0, getPageCount() - 1);
        this.keyMapping = keyMapping;
        this.bindingLabel = keyMapping.getTranslatedKeyMessage();
    }

    @Override
    protected void init() {
        this.metrics = WheelGeometry.createMetrics(this.width, this.height);
        List<SlotGeometry> slots = new ArrayList<>(WheelGeometry.SLOT_COUNT);
        for (int slotIndex = 0; slotIndex < WheelGeometry.SLOT_COUNT; slotIndex++) {
            slots.add(WheelGeometry.createSlot(slotIndex, this.metrics));
        }
        this.slotGeometries = List.copyOf(slots);
        this.centerXPoints = WheelGeometry.createHexagonXPoints(
            this.metrics.centerX(),
            this.metrics.centerRadius()
        );
        this.centerYPoints = WheelGeometry.createHexagonYPoints(
            this.metrics.centerY(),
            this.metrics.centerRadius()
        );
        this.addRenderableWidget(Button.builder(
            Component.translatable("screen.emote.wheel.edit"),
            ignoredButton -> this.controller.openShortcutEditor()
        ).bounds(Math.max(4, this.width - EDIT_BUTTON_WIDTH - 4), this.height - 24, EDIT_BUTTON_WIDTH, 20).build());
        updateHoveredSlot(this.width / 2.0D, this.height / 2.0D);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean isInGameUi() {
        return true;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        graphics.fillGradient(0, 0, this.width, this.height, BACKGROUND_TOP_COLOR, BACKGROUND_BOTTOM_COLOR);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        updateHoveredSlot(mouseX, mouseY);

        WheelMetrics metrics = this.metrics;
        List<PlayableEmote> pageEmotes = getCurrentPageEntries();

        graphics.centeredText(this.font, this.title, metrics.centerX(), 18, TITLE_COLOR);

        for (int slotIndex = 0; slotIndex < WheelGeometry.SLOT_COUNT; slotIndex++) {
            SlotGeometry slot = this.slotGeometries.get(slotIndex);
            PlayableEmote playableEmote = slotIndex < pageEmotes.size() ? pageEmotes.get(slotIndex) : null;
            boolean hovered = slotIndex == this.hoveredSlotIndex;
            drawSlot(graphics, slot, playableEmote, hovered);
        }

        drawCenterHex(graphics, metrics);
        drawFooter(graphics, metrics, pageEmotes);
    }

    @Override
    public void mouseMoved(double x, double y) {
        updateHoveredSlot(x, y);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }

        updateHoveredSlot(event.x(), event.y());

        if (event.button() == LEFT_MOUSE_BUTTON) {
            changePage(-1, event.x(), event.y());
            return true;
        }

        if (event.button() == RIGHT_MOUSE_BUTTON) {
            changePage(1, event.x(), event.y());
            return true;
        }

        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        updateHoveredSlot(event.x(), event.y());
        if (this.keyMapping.matchesMouse(event)) {
            handleBindingReleased();
            return true;
        }
        return event.button() == LEFT_MOUSE_BUTTON || event.button() == RIGHT_MOUSE_BUTTON;
    }

    @Override
    public boolean keyReleased(@NonNull KeyEvent event) {
        if (this.keyMapping.matches(event)) {
            handleBindingReleased();
            return true;
        }
        return super.keyReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (getPageCount() <= 1) {
            return false;
        }

        int direction;
        if (scrollY > 0.0D || scrollX > 0.0D) {
            direction = -1;
        } else if (scrollY < 0.0D || scrollX < 0.0D) {
            direction = 1;
        } else {
            return false;
        }

        changePage(direction, x, y);
        return true;
    }

    public void handleBindingReleased() {
        if (!selectHoveredSlot()) {
            this.onClose();
        }
    }

    private void changePage(int direction, double mouseX, double mouseY) {
        if (getPageCount() <= 1) {
            return;
        }

        this.pageIndex = Math.floorMod(this.pageIndex + direction, getPageCount());
        updateHoveredSlot(mouseX, mouseY);
    }

    private void drawSlot(GuiGraphicsExtractor graphics, SlotGeometry slot, PlayableEmote playableEmote, boolean hovered) {
        int fillColor = playableEmote == null
            ? SLOT_EMPTY_FILL_COLOR
            : hovered
            ? SLOT_HIGHLIGHT_FILL_COLOR
            : SLOT_FILL_COLOR;
        drawHex(graphics, slot.xPoints(), slot.yPoints(), fillColor, SLOT_BORDER_COLOR);

        if (playableEmote == null) {
            return;
        }

        List<FormattedCharSequence> lines = this.font.split(Component.literal(playableEmote.displayName()), slot.textWidth());
        int visibleLineCount = Math.min(2, lines.size());
        int lineStartY = slot.centerY() - (visibleLineCount * this.font.lineHeight) / 2;
        for (int lineIndex = 0; lineIndex < visibleLineCount; lineIndex++) {
            graphics.centeredText(
                this.font,
                lines.get(lineIndex),
                slot.centerX(),
                lineStartY + lineIndex * this.font.lineHeight,
                TITLE_COLOR
            );
        }
    }

    private void drawCenterHex(GuiGraphicsExtractor graphics, WheelMetrics metrics) {
        drawHex(graphics, this.centerXPoints, this.centerYPoints, CENTER_FILL_COLOR, CENTER_BORDER_COLOR);

        if (this.emotes.isEmpty()) {
            graphics.centeredText(this.font, Component.translatable("screen.emote.wheel.center.no_shortcuts"), metrics.centerX(), metrics.centerY() - 10, TITLE_COLOR);
            graphics.centeredText(this.font, Component.translatable("screen.emote.wheel.center.selected"), metrics.centerX(), metrics.centerY() + 2, TITLE_COLOR);
            return;
        }

        graphics.centeredText(this.font, (this.pageIndex + 1) + "/" + getPageCount(), metrics.centerX(), metrics.centerY() - 10, TITLE_COLOR);
        graphics.centeredText(this.font, Component.translatable("screen.emote.wheel.center.release"), metrics.centerX(), metrics.centerY() + 2, BODY_COLOR);
        graphics.centeredText(this.font, Component.translatable("screen.emote.wheel.center.to_play"), metrics.centerX(), metrics.centerY() + 12, BODY_COLOR);
    }

    private void drawFooter(GuiGraphicsExtractor graphics, WheelMetrics metrics, List<PlayableEmote> pageEmotes) {
        int footerTop = Math.min(this.height - 70, metrics.centerY() + metrics.ringRadius() + metrics.slotRadius() + 14);
        PlayableEmote hoveredEmote = this.hoveredSlotIndex >= 0 && this.hoveredSlotIndex < pageEmotes.size()
            ? pageEmotes.get(this.hoveredSlotIndex)
            : null;

        if (hoveredEmote != null) {
            graphics.centeredText(this.font, Component.literal(hoveredEmote.displayName()), metrics.centerX(), footerTop, TITLE_COLOR);
            graphics.textWithWordWrap(
                this.font,
                Component.literal(hoveredEmote.description()),
                metrics.centerX() - metrics.descriptionWidth() / 2,
                footerTop + 14,
                metrics.descriptionWidth(),
                BODY_COLOR,
                true
            );
            return;
        }

        if (this.emotes.isEmpty()) {
            graphics.centeredText(this.font, Component.translatable("screen.emote.wheel.footer.no_shortcuts"), metrics.centerX(), footerTop + 8, BODY_COLOR);
            return;
        }

        graphics.centeredText(
            this.font,
            Component.translatable("screen.emote.wheel.footer.release_to_play", this.bindingLabel),
            metrics.centerX(),
            footerTop,
            BODY_COLOR
        );
        graphics.centeredText(this.font, Component.translatable("screen.emote.wheel.footer.close"), metrics.centerX(), footerTop + 14, MUTED_COLOR);
        if (getPageCount() > 1) {
            graphics.centeredText(this.font, Component.translatable("screen.emote.wheel.footer.page_click"), metrics.centerX(), footerTop + 28, MUTED_COLOR);
        }
    }

    private boolean selectHoveredSlot() {
        PlayableEmote playableEmote = getEntryAt(this.hoveredSlotIndex);
        if (playableEmote == null) {
            return false;
        }

        this.onClose();
        this.controller.play(playableEmote);
        return true;
    }

    private PlayableEmote getEntryAt(int slotIndex) {
        if (slotIndex < 0) {
            return null;
        }

        int emoteIndex = this.pageIndex * WheelGeometry.SLOT_COUNT + slotIndex;
        return emoteIndex >= 0 && emoteIndex < this.emotes.size()
            ? this.emotes.get(emoteIndex)
            : null;
    }

    private List<PlayableEmote> getCurrentPageEntries() {
        int startIndex = Math.min(this.pageIndex * WheelGeometry.SLOT_COUNT, this.emotes.size());
        int endIndex = Math.min(startIndex + WheelGeometry.SLOT_COUNT, this.emotes.size());
        return this.emotes.subList(startIndex, endIndex);
    }

    private int getPageCount() {
        return Math.max(1, (this.emotes.size() + WheelGeometry.SLOT_COUNT - 1) / WheelGeometry.SLOT_COUNT);
    }

    private void updateHoveredSlot(double mouseX, double mouseY) {
        this.hoveredSlotIndex = -1;

        for (int slotIndex = 0; slotIndex < WheelGeometry.SLOT_COUNT; slotIndex++) {
            if (getEntryAt(slotIndex) == null) {
                continue;
            }

            SlotGeometry slot = this.slotGeometries.get(slotIndex);
            if (WheelGeometry.containsPoint(slot.xPoints(), slot.yPoints(), mouseX, mouseY)) {
                this.hoveredSlotIndex = slotIndex;
                return;
            }
        }
    }

    private void drawHex(GuiGraphicsExtractor graphics, int[] xPoints, int[] yPoints, int fillColor, int borderColor) {
        fillPolygon(graphics, xPoints, yPoints, borderColor);
        int centerX = WheelGeometry.average(xPoints);
        int centerY = WheelGeometry.average(yPoints);
        int innerRadius = Math.max(8, WheelGeometry.estimateRadius(xPoints, centerX) - 3);
        fillPolygon(
            graphics,
            WheelGeometry.createHexagonXPoints(centerX, innerRadius),
            WheelGeometry.createHexagonYPoints(centerY, innerRadius),
            fillColor
        );
    }

    private void fillPolygon(GuiGraphicsExtractor graphics, int[] xPoints, int[] yPoints, int color) {
        if (yPoints.length == 0) {
            return;
        }

        int minY = yPoints[0];
        int maxY = yPoints[0];
        for (int y : yPoints) {
            if (y < minY) {
                minY = y;
            }

            if (y > maxY) {
                maxY = y;
            }
        }

        double[] intersections = new double[xPoints.length];

        for (int y = minY; y <= maxY; y++) {
            int intersectionCount = 0;
            double scanY = y + 0.5D;

            for (int currentIndex = 0, previousIndex = xPoints.length - 1; currentIndex < xPoints.length; previousIndex = currentIndex++) {
                int currentY = yPoints[currentIndex];
                int previousY = yPoints[previousIndex];
                if (currentY == previousY) {
                    continue;
                }

                double lowerY = Math.min(currentY, previousY);
                double upperY = Math.max(currentY, previousY);
                if (scanY < lowerY || scanY >= upperY) {
                    continue;
                }

                int currentX = xPoints[currentIndex];
                int previousX = xPoints[previousIndex];
                intersections[intersectionCount++] = currentX + (scanY - currentY) * (previousX - currentX) / (previousY - currentY);
            }

            Arrays.sort(intersections, 0, intersectionCount);
            for (int index = 0; index + 1 < intersectionCount; index += 2) {
                int startX = Mth.floor(intersections[index]);
                int endX = Mth.ceil(intersections[index + 1]);
                graphics.fill(startX, y, endX, y + 1, color);
            }
        }
    }
}
