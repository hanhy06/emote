package io.github.hanhy06.emote.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import io.github.hanhy06.emote.application.EmoteSummary;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix3x2fStack;
import org.jspecify.annotations.NonNull;

import java.util.List;

@Environment(EnvType.CLIENT)
public class WheelScreen extends Screen {
    private static final int ACTION_BUTTON_WIDTH = 50;
    private static final int ACTION_BUTTON_GAP = 4;
    private static final int ACTION_BUTTONS_WIDTH = 2 * (ACTION_BUTTON_WIDTH + ACTION_BUTTON_GAP);
    private static final int TITLE_TOP = 8;
    private static final int LEFT_MOUSE_BUTTON = InputConstants.MOUSE_BUTTON_LEFT;
    private static final int RIGHT_MOUSE_BUTTON = InputConstants.MOUSE_BUTTON_RIGHT;
    private static final int BACKGROUND_COLOR = 0x40101010;
    private static final int TITLE_COLOR = 0xFFF7FAFC;
    private static final int BODY_COLOR = 0xFFD1D9DF;
    private static final int MUTED_COLOR = 0xFF9DB0BC;

    private final WheelController controller;
    private final List<EmoteSummary> emotes;
    private final boolean hasEmotes;
    private final int pageCount;
    private final KeyMapping keyMapping;
    private final Component bindingLabel;
    private final ScrollAccumulator scrollAccumulator = new ScrollAccumulator();

    private WheelGeometry wheel;
    private int pageIndex;
    private List<EmoteSummary> pageEmotes;

    public WheelScreen(WheelController controller, List<EmoteSummary> emotes, int pageIndex, KeyMapping keyMapping) {
        super(Component.translatable("screen.emote.wheel.title"));
        this.controller = controller;
        this.emotes = emotes;
        int lastPlayableSlot = emotes.size() - 1;
        while (lastPlayableSlot >= 0 && emotes.get(lastPlayableSlot) == null) {
            lastPlayableSlot--;
        }
        this.hasEmotes = lastPlayableSlot >= 0;
        this.pageCount = Math.max(1, Math.ceilDiv(lastPlayableSlot + 1, WheelGeometry.SLOT_COUNT));
        setPage(Math.clamp(pageIndex, 0, this.pageCount - 1));
        this.keyMapping = keyMapping;
        this.bindingLabel = keyMapping.getTranslatedKeyMessage();
    }

    @Override
    protected void init() {
        var slotMask = WheelRenderer.loadSlotMask(this.minecraft.getResourceManager());
        this.wheel = new WheelGeometry(this.width, this.height, System.nanoTime(), slotMask);
        int searchX = Math.max(ACTION_BUTTON_GAP, this.width - ACTION_BUTTONS_WIDTH);
        int editX = searchX + ACTION_BUTTON_WIDTH + ACTION_BUTTON_GAP;
        this.addRenderableWidget(Button.builder(
            Component.translatable("screen.emote.wheel.search"),
            ignoredButton -> this.controller.openSearch(this.keyMapping)
        ).bounds(searchX, this.height - 24, ACTION_BUTTON_WIDTH, 20).build());
        this.addRenderableWidget(Button.builder(
            Component.translatable("screen.emote.wheel.edit"),
            ignoredButton -> this.controller.openShortcutEditor(this.pageIndex)
        ).bounds(editX, this.height - 24, ACTION_BUTTON_WIDTH, 20).build());
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
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, BACKGROUND_COLOR);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        long now = System.nanoTime();
        updateHoveredSlot(mouseX, mouseY, now);
        EmoteSummary hoveredEmote = emoteAt(this.wheel.selected());

        WheelRenderer.draw(graphics, this.font, this.wheel, this.pageEmotes, now, false);
        drawTitle(graphics, hoveredEmote);
        drawCenter(graphics);
        drawFooter(graphics, hoveredEmote);
    }

    @Override
    public void mouseMoved(double x, double y) {
        updateHoveredSlot(x, y, System.nanoTime());
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }

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
        if (this.keyMapping.matchesMouse(event)) {
            handleBindingReleased(event.x(), event.y());
            return true;
        }
        updateHoveredSlot(event.x(), event.y(), System.nanoTime());
        return event.button() == LEFT_MOUSE_BUTTON || event.button() == RIGHT_MOUSE_BUTTON;
    }

    @Override
    public boolean keyReleased(@NonNull KeyEvent event) {
        if (this.keyMapping.matches(event)) {
            Window window = this.minecraft.getWindow();
            MouseHandler mouseHandler = this.minecraft.mouseHandler;
            handleBindingReleased(mouseHandler.getScaledXPos(window), mouseHandler.getScaledYPos(window));
            return true;
        }
        return super.keyReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (this.pageCount <= 1) {
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

    private void handleBindingReleased(double mouseX, double mouseY) {
        EmoteSummary hoveredEmote = emoteAt(this.wheel.slotAt(mouseX, mouseY, System.nanoTime()));
        this.onClose();
        if (hoveredEmote != null) {
            this.controller.play(hoveredEmote);
        }
    }

    private void changePage(int direction, double mouseX, double mouseY) {
        int nextPage = Math.floorMod((long) this.pageIndex + direction, this.pageCount);
        if (nextPage == this.pageIndex) {
            return;
        }

        setPage(nextPage);
        long now = System.nanoTime();
        this.wheel.select(playableHoveredSlotAt(mouseX, mouseY, now), now);
        playWheelClick();
    }

    private void setPage(int pageIndex) {
        this.pageIndex = pageIndex;
        this.pageEmotes = WheelGeometry.page(this.emotes, pageIndex);
    }

    private void drawTitle(GuiGraphicsExtractor graphics, EmoteSummary hoveredEmote) {
        Component text = hoveredEmote == null ? this.title : Component.literal(hoveredEmote.name());
        float scale = hoveredEmote == null ? 1.0F : 1.25F;
        int rimTop = this.wheel.centerY - this.wheel.outerRadius - (int) Math.ceil(this.wheel.lift);
        int titleBottom = Math.clamp(rimTop - 3, TITLE_TOP + 1, WheelGeometry.TITLE_BOTTOM);
        float centerY = (TITLE_TOP + titleBottom) / 2.0F;
        WheelRenderer.drawWrappedLabel(graphics, this.font, text, this.wheel.centerX, centerY, Math.max(1, this.width - 24), titleBottom - TITLE_TOP, scale, TITLE_COLOR);
    }

    private void drawCenter(GuiGraphicsExtractor graphics) {
        boolean empty = !this.hasEmotes;
        Component first = Component.translatable(empty ? "screen.emote.wheel.center.no_shortcuts" : "screen.emote.wheel.center.release");
        Component second = Component.translatable(empty ? "screen.emote.wheel.center.selected" : "screen.emote.wheel.center.to_play");
        float scale = Math.min(1.0F, this.wheel.scale * 60 /
            (float) Math.hypot(Math.max(this.font.width(first), this.font.width(second)), this.font.lineHeight * 2 + 2));
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix().translate(this.wheel.centerX, this.wheel.centerY).scale(scale);
        graphics.centeredText(this.font, first, 0, -this.font.lineHeight - 1, BODY_COLOR);
        graphics.centeredText(this.font, second, 0, 1, TITLE_COLOR);
        pose.popMatrix();
    }

    private void drawFooter(GuiGraphicsExtractor graphics, EmoteSummary hoveredEmote) {
        int pageY = Math.min(this.height - WheelGeometry.PAGE_TOP_FROM_BOTTOM,
            this.wheel.centerY + this.wheel.outerRadius + 9);
        graphics.centeredText(this.font, (this.pageIndex + 1) + " / " + this.pageCount, this.wheel.centerX, pageY, MUTED_COLOR);
        int footerTop = pageY + 12;
        int footerWidth = Math.max(8, this.width - 2 * (ACTION_BUTTONS_WIDTH + ACTION_BUTTON_GAP));

        if (hoveredEmote != null && !hoveredEmote.description().isBlank()) {
            int descriptionWidth = Math.min(280, footerWidth);
            Component description = Component.literal(hoveredEmote.description());
            List<FormattedCharSequence> lines = this.font.split(description, descriptionWidth);
            int lineSpacing = this.font.lineHeight + 2;
            int descriptionHeight = Math.max(1, this.height - footerTop - 4);
            float scale = Math.min(1.0F, (float) descriptionHeight / Math.max(1, lines.size() * lineSpacing));
            if (scale < 1.0F) {
                lines = this.font.split(description, (int) (descriptionWidth / scale));
            }
            int x = this.wheel.centerX - descriptionWidth / 2;
            Matrix3x2fStack pose = graphics.pose();
            pose.pushMatrix().translate(x, footerTop).scale(scale);
            for (int i = 0; i < lines.size(); i++) {
                graphics.text(this.font, lines.get(i), 0, i * lineSpacing, BODY_COLOR, true);
            }
            pose.popMatrix();
            return;
        }

        if (!this.hasEmotes) {
            drawFooterHint(graphics, Component.translatable("screen.emote.wheel.footer.no_shortcuts"), footerTop, footerWidth, BODY_COLOR);
        } else {
            drawFooterHint(graphics, Component.translatable("screen.emote.wheel.footer.release_to_play", this.bindingLabel), footerTop, footerWidth, BODY_COLOR);
            drawFooterHint(graphics, Component.translatable("screen.emote.wheel.footer.close"), footerTop + 11, footerWidth, MUTED_COLOR);
        }

        if (this.pageCount > 1) {
            drawFooterHint(graphics, Component.translatable("screen.emote.wheel.footer.page_click"), footerTop + 22, footerWidth, MUTED_COLOR);
        }
    }

    private void drawFooterHint(GuiGraphicsExtractor graphics, Component text, int y, int maxWidth, int color) {
        float scale = Math.min(1.0F, (float) maxWidth / this.font.width(text));
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix().translate(this.wheel.centerX, y).scale(scale);
        graphics.centeredText(this.font, text, 0, 0, color);
        pose.popMatrix();
    }

    private EmoteSummary emoteAt(int slot) {
        return slot >= 0 && slot < this.pageEmotes.size() ? this.pageEmotes.get(slot) : null;
    }

    private void updateHoveredSlot(double mouseX, double mouseY, long now) {
        int slot = playableHoveredSlotAt(mouseX, mouseY, now);
        if (this.wheel.select(slot, now) && slot >= 0) {
            playWheelClick();
        }
    }

    private int playableHoveredSlotAt(double mouseX, double mouseY, long now) {
        int slot = this.wheel.hoveredSlotAt(mouseX, mouseY, now);
        return emoteAt(slot) == null ? -1 : slot;
    }

    private void playWheelClick() {
        this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.15F, 0.126F));
    }

    private static final class ScrollAccumulator {
        private static final double ROUNDING_TOLERANCE = 1.0E-9D;
        private double remainder;

        int add(double amount) {
            if (this.remainder * amount < 0.0D) {
                this.remainder = 0.0D;
            }
            this.remainder += amount / 2.0D;
            int pages = (int) (this.remainder + Math.copySign(ROUNDING_TOLERANCE, this.remainder));
            this.remainder -= pages;
            if (Math.abs(this.remainder) < ROUNDING_TOLERANCE) {
                this.remainder = 0.0D;
            }
            return pages;
        }
    }
}
