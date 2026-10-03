package io.github.hanhy06.emote.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import io.github.hanhy06.emote.application.EmoteSummary;
import io.github.hanhy06.emote.client.WheelGeometry.SlotGeometry;
import io.github.hanhy06.emote.client.WheelGeometry.WheelMetrics;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NonNull;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Environment(EnvType.CLIENT)
public class WheelScreen extends Screen {
    private static final int EDIT_BUTTON_WIDTH = 50;
    private static final int LEFT_MOUSE_BUTTON = InputConstants.MOUSE_BUTTON_LEFT;
    private static final int RIGHT_MOUSE_BUTTON = InputConstants.MOUSE_BUTTON_RIGHT;
    private static final int BACKGROUND_TOP_COLOR = 0x18101010;
    private static final int BACKGROUND_BOTTOM_COLOR = 0x30101010;
    private static final int SLOT_FILL_COLOR = 0x80252525;
    private static final int SLOT_HIGHLIGHT_FILL_COLOR = 0x990078D7;
    private static final int SLOT_EMPTY_FILL_COLOR = 0x501C1C1C;
    private static final int CENTER_FILL_COLOR = 0x70202020;
    private static final int TITLE_COLOR = 0xFFF7FAFC;
    private static final int BODY_COLOR = 0xFFD1D9DF;
    private static final int MUTED_COLOR = 0xFF9DB0BC;

    private final WheelController controller;
    private final List<EmoteSummary> emotes;
    private final KeyMapping keyMapping;
    private final Component bindingLabel;
    private final ScrollAccumulator scrollAccumulator = new ScrollAccumulator();

    private WheelMetrics metrics;
    private List<SlotGeometry> slotGeometries = List.of();
    private int pageIndex;
    private int hoveredSlotIndex = -1;
    private DynamicTexture wheelTexture;
    private int[] textureSlotColors = new int[0];

    public WheelScreen(WheelController controller, List<EmoteSummary> emotes, int pageIndex, KeyMapping keyMapping) {
        super(Component.translatable("screen.emote.wheel.title"));
        this.controller = controller;
        this.emotes = List.copyOf(emotes);
        this.pageIndex = Math.clamp(pageIndex, 0, getPageCount() - 1);
        this.keyMapping = keyMapping;
        this.bindingLabel = keyMapping.getTranslatedKeyMessage();
    }

    @Override
    protected void init() {
        if (this.wheelTexture != null) {
            this.wheelTexture.close();
            this.wheelTexture = null;
        }
        this.textureSlotColors = new int[0];
        this.metrics = WheelGeometry.createMetrics(this.width, this.height);
        List<SlotGeometry> slots = new ArrayList<>(WheelGeometry.SLOT_COUNT);
        for (int slotIndex = 0; slotIndex < WheelGeometry.SLOT_COUNT; slotIndex++) {
            slots.add(WheelGeometry.createSlot(slotIndex, this.metrics));
        }
        this.slotGeometries = List.copyOf(slots);
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
    public void removed() {
        if (this.wheelTexture != null) {
            this.wheelTexture.close();
            this.wheelTexture = null;
        }
        super.removed();
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
        List<EmoteSummary> pageEmotes = getCurrentPageEntries();

        graphics.centeredText(this.font, this.title, metrics.centerX(), 18, TITLE_COLOR);
        drawWheel(graphics, pageEmotes);

        for (int slotIndex = 0; slotIndex < WheelGeometry.SLOT_COUNT; slotIndex++) {
            SlotGeometry slot = this.slotGeometries.get(slotIndex);
            EmoteSummary emoteSummary = slotIndex < pageEmotes.size() ? pageEmotes.get(slotIndex) : null;
            drawSlot(graphics, slot, emoteSummary);
        }

        drawCenter(graphics, metrics, pageEmotes);
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

        double amount = Math.abs(scrollY) >= Math.abs(scrollX) ? scrollY : scrollX;
        if (amount == 0.0D || !Double.isFinite(amount)) {
            return false;
        }
        int pages = this.scrollAccumulator.add(amount);
        if (pages != 0) {
            changePage(-pages, x, y);
        }
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

    private void drawSlot(GuiGraphicsExtractor graphics, SlotGeometry slot, EmoteSummary emoteSummary) {
        if (emoteSummary == null) {
            return;
        }

        graphics.centeredText(
            this.font,
            fitText(emoteSummary.displayName(), slot.textWidth()),
            slot.centerX(),
            slot.centerY() - this.font.lineHeight / 2,
            TITLE_COLOR
        );
    }

    private void drawCenter(GuiGraphicsExtractor graphics, WheelMetrics metrics, List<EmoteSummary> pageEmotes) {
        if (this.emotes.isEmpty()) {
            graphics.centeredText(this.font, Component.translatable("screen.emote.wheel.center.no_shortcuts"), metrics.centerX(), metrics.centerY() - 10, TITLE_COLOR);
            graphics.centeredText(this.font, Component.translatable("screen.emote.wheel.center.selected"), metrics.centerX(), metrics.centerY() + 2, TITLE_COLOR);
            return;
        }

        EmoteSummary hoveredEmote = this.hoveredSlotIndex >= 0 && this.hoveredSlotIndex < pageEmotes.size()
            ? pageEmotes.get(this.hoveredSlotIndex)
            : null;
        if (hoveredEmote != null) {
            graphics.centeredText(this.font, Component.literal(hoveredEmote.displayName()), metrics.centerX(), metrics.centerY() - 12, TITLE_COLOR);
        }
        graphics.centeredText(this.font, Component.translatable("screen.emote.wheel.center.release"), metrics.centerX(), metrics.centerY() + 2, BODY_COLOR);
        graphics.centeredText(this.font, Component.translatable("screen.emote.wheel.center.to_play"), metrics.centerX(), metrics.centerY() + 12, BODY_COLOR);
    }

    private void drawFooter(GuiGraphicsExtractor graphics, WheelMetrics metrics, List<EmoteSummary> pageEmotes) {
        int pageY = metrics.centerY() + metrics.outerRadius() + 8;
        graphics.centeredText(this.font, (this.pageIndex + 1) + " / " + getPageCount(), metrics.centerX(), pageY, MUTED_COLOR);
        int footerTop = pageY + 18;
        EmoteSummary hoveredEmote = this.hoveredSlotIndex >= 0 && this.hoveredSlotIndex < pageEmotes.size()
            ? pageEmotes.get(this.hoveredSlotIndex)
            : null;

        if (hoveredEmote != null) {
            graphics.textWithWordWrap(
                this.font,
                Component.literal(hoveredEmote.description()),
                metrics.centerX() - metrics.descriptionWidth() / 2,
                footerTop,
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
        EmoteSummary emoteSummary = getEntryAt(this.hoveredSlotIndex);
        if (emoteSummary == null) {
            return false;
        }

        this.onClose();
        this.controller.play(emoteSummary);
        return true;
    }

    private EmoteSummary getEntryAt(int slotIndex) {
        if (slotIndex < 0) {
            return null;
        }

        int emoteIndex = this.pageIndex * WheelGeometry.SLOT_COUNT + slotIndex;
        return emoteIndex >= 0 && emoteIndex < this.emotes.size()
            ? this.emotes.get(emoteIndex)
            : null;
    }

    private List<EmoteSummary> getCurrentPageEntries() {
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

    static final class ScrollAccumulator {
        private double remainder;

        int add(double amount) {
            if (amount == 0.0D || !Double.isFinite(amount)) {
                return 0;
            }
            if (this.remainder * amount < 0.0D) {
                this.remainder = 0.0D;
            }
            this.remainder += amount / 5.0D;
            int pages = (int) (this.remainder + Math.copySign(1.0E-9D, this.remainder));
            this.remainder -= pages;
            if (Math.abs(this.remainder) < 1.0E-9D) {
                this.remainder = 0.0D;
            }
            return pages;
        }
    }

    private Component fitText(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) {
            return Component.literal(text);
        }

        String ellipsis = "...";
        return Component.literal(this.font.plainSubstrByWidth(text, Math.max(0, maxWidth - this.font.width(ellipsis))) + ellipsis);
    }

    private void drawWheel(GuiGraphicsExtractor graphics, List<EmoteSummary> pageEmotes) {
        int[] colors = new int[WheelGeometry.SLOT_COUNT];
        for (int index = 0; index < colors.length; index++) {
            colors[index] = index >= pageEmotes.size() ? SLOT_EMPTY_FILL_COLOR
                : index == this.hoveredSlotIndex ? SLOT_HIGHLIGHT_FILL_COLOR : SLOT_FILL_COLOR;
        }
        if (this.wheelTexture == null || !Arrays.equals(colors, this.textureSlotColors)) {
            BufferedImage image = WheelGeometry.createWheelImage(this.metrics, Math.max(1, this.minecraft.getWindow().getGuiScale()), colors, CENTER_FILL_COLOR);
            NativeImage pixels = this.wheelTexture == null
                ? new NativeImage(image.getWidth(), image.getHeight(), false)
                : this.wheelTexture.getPixels();
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    pixels.setPixel(x, y, image.getRGB(x, y));
                }
            }
            if (this.wheelTexture == null) {
                this.wheelTexture = new DynamicTexture(() -> "Emote wheel", pixels);
            } else {
                this.wheelTexture.upload();
            }
            this.textureSlotColors = colors;
        }
        int radius = this.metrics.outerRadius() + 1;
        graphics.blit(
            this.wheelTexture.getTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
            this.metrics.centerX() - radius, this.metrics.centerY() - radius,
            this.metrics.centerX() + radius, this.metrics.centerY() + radius,
            0.0F, 1.0F, 0.0F, 1.0F
        );
    }
}
