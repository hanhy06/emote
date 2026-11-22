package io.github.hanhy06.emote.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import io.github.hanhy06.emote.application.EmoteSummary;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.GuiPipeline;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.pipeline.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix3x2fStack;
import org.jspecify.annotations.NonNull;

import java.util.List;

@Environment(EnvType.CLIENT)
public class WheelScreen extends Screen {
    private static final int EDIT_BUTTON_WIDTH = 50;
    private static final int TITLE_TOP = 8;
    private static final Identifier SLOT = Identifier.parse("emote:textures/gui/wheel/slot.png");
    private static final Identifier HUB = Identifier.parse("emote:textures/gui/wheel/hub.png");
    private static final Identifier INDICATOR = Identifier.parse("emote:textures/gui/wheel/indicator.png");
    private static final GuiPipeline WHEEL_SHAPE = GuiPipeline.of(() -> RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
        .withLocation(Identifier.parse("emote:pipeline/wheel_shape"))
        .withVertexShader("core/position_tex_color")
        .withFragmentShader(Identifier.parse("emote:core/wheel_shape"))
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
        .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
        .withPrimitiveTopology(PrimitiveTopology.QUADS)
        .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
        .build());
    private static final String ELLIPSIS = "…";
    private static final int LEFT_MOUSE_BUTTON = InputConstants.MOUSE_BUTTON_LEFT;
    private static final int RIGHT_MOUSE_BUTTON = InputConstants.MOUSE_BUTTON_RIGHT;
    private static final int BACKGROUND_COLOR = 0x40101010;
    private static final int SLOT_FILL_COLOR = 0xF33B4752;
    private static final int SLOT_HIGHLIGHT_FILL_COLOR = 0xFF265B83;
    private static final int SLOT_EMPTY_FILL_COLOR = 0xC21C242B;
    private static final int HUB_COLOR = 0xFA171F27;
    private static final int INDICATOR_COLOR = 0xFF79BDEF;
    private static final int TITLE_COLOR = 0xFFF7FAFC;
    private static final int BODY_COLOR = 0xFFD1D9DF;
    private static final int MUTED_COLOR = 0xFF9DB0BC;

    private final WheelController controller;
    private final List<EmoteSummary> emotes;
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
        this.emotes = List.copyOf(emotes);
        this.pageCount = Math.max(1, Math.ceilDiv(this.emotes.size(), WheelGeometry.SLOT_COUNT));
        setPage(Math.clamp(pageIndex, 0, this.pageCount - 1));
        this.keyMapping = keyMapping;
        this.bindingLabel = keyMapping.getTranslatedKeyMessage();
    }

    @Override
    protected void init() {
        this.wheel = new WheelGeometry(this.width, this.height, System.nanoTime());
        this.addRenderableWidget(Button.builder(
            Component.translatable("screen.emote.wheel.edit"),
            ignoredButton -> this.controller.openShortcutEditor()
        ).bounds(Math.max(4, this.width - EDIT_BUTTON_WIDTH - 4), this.height - 24, EDIT_BUTTON_WIDTH, 20).build());
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
    public void extractBackground(GuiGraphicsExtractor graphics) {
        graphics.fill(0, 0, this.width, this.height, BACKGROUND_COLOR);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics) {
        super.extractRenderState(graphics);
        Window window = this.minecraft.getWindow();
        MouseHandler mouseHandler = this.minecraft.mouseHandler;
        updateHoveredSlot(mouseHandler.getScaledXPos(window), mouseHandler.getScaledYPos(window));

        long now = System.nanoTime();
        EmoteSummary hoveredEmote = hoveredEmote();

        drawWheel(graphics, now);
        drawTitle(graphics, hoveredEmote);
        drawCenter(graphics);
        drawFooter(graphics, hoveredEmote);
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

    private void handleBindingReleased() {
        EmoteSummary hoveredEmote = hoveredEmote();
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
        this.wheel.select(this.wheel.slotAt(mouseX, mouseY, this.pageEmotes.size()), System.nanoTime());
        playWheelClick();
    }

    private void setPage(int pageIndex) {
        this.pageIndex = pageIndex;
        int start = pageIndex * WheelGeometry.SLOT_COUNT;
        int count = Math.min(WheelGeometry.SLOT_COUNT, this.emotes.size() - start);
        this.pageEmotes = this.emotes.subList(start, start + count);
    }

    private void drawWheel(GuiGraphicsExtractor graphics, long now) {
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix().translate(this.wheel.centerX, this.wheel.centerY);
        float progress = this.wheel.selectionProgress(now);
        for (int i = 0; i < WheelGeometry.SLOT_COUNT; i++) {
            float entrance = this.wheel.entrance(i, now);
            float weight = this.wheel.weight(i, progress);
            float angle = (float) (i * WheelGeometry.SLOT_ANGLE);
            float scale = this.wheel.scale * (0.93F + 0.07F * entrance);
            float lift = this.wheel.lift * weight;
            boolean hasEmote = i < this.pageEmotes.size();
            int color = hasEmote ? ARGB.srgbLerp(weight, SLOT_FILL_COLOR, SLOT_HIGHLIGHT_FILL_COLOR) : SLOT_EMPTY_FILL_COLOR;
            pose.pushMatrix().rotate(angle).translate(0, -lift).scale(scale);
            drawWheelPart(graphics, SLOT, -64, -106, 128, 80, ARGB.color(Math.round(ARGB.alpha(color) * entrance), color));
            pose.popMatrix();

            if (hasEmote && entrance > 0) {
                float distance = WheelGeometry.LABEL_RADIUS * scale + lift;
                float x = (float) Math.sin(angle) * distance;
                float y = -(float) Math.cos(angle) * distance;
                drawSlotLabel(graphics, this.pageEmotes.get(i).name(), i == this.wheel.selected(), x, y,
                    ARGB.color(Math.round(255 * entrance), TITLE_COLOR));
            }
        }

        pose.scale(this.wheel.scale);
        drawWheelPart(graphics, HUB, -40, -40, 80, 80, HUB_COLOR);
        float opacity = this.wheel.indicatorOpacity(progress);
        if (opacity > 0) {
            pose.rotate((float) this.wheel.indicatorAngle(now));
            drawWheelPart(graphics, INDICATOR, -17, -41, 34, 10, ARGB.color(Math.round(255 * opacity), INDICATOR_COLOR));
        }
        pose.popMatrix();
    }

    private static void drawWheelPart(GuiGraphicsExtractor graphics, Identifier texture, int x, int y, int width, int height, int color) {
        graphics.blit(WHEEL_SHAPE, texture, x, y, 0, 0, width, height, width, height, color);
    }

    private void drawSlotLabel(GuiGraphicsExtractor graphics, String name, boolean selected, float x, float y, int color) {
        int width = this.wheel.textWidth;
        String singleLine = name.replace('\n', ' ').replace('\r', ' ');
        boolean tooWide = this.font.width(singleLine) > width;
        if (selected && (tooWide || !singleLine.equals(name))) {
            int height = Math.max(4, Math.min(Math.round(this.wheel.outerRadius * 0.34F), 3 * (this.font.lineHeight + 2)));
            drawWrappedLabel(graphics, Component.literal(name), x, y, width, height, 1, color);
            return;
        }

        if (tooWide) {
            int textWidth = Math.max(0, width - this.font.width(ELLIPSIS));
            singleLine = this.font.plainSubstrByWidth(singleLine, textWidth).stripTrailing() + ELLIPSIS;
        }
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix().translate(x, y - this.font.lineHeight / 2.0F);
        graphics.centeredText(this.font, singleLine, 0, 0, color);
        pose.popMatrix();
    }

    private void drawTitle(GuiGraphicsExtractor graphics, EmoteSummary hoveredEmote) {
        Component text = hoveredEmote == null ? this.title : Component.literal(hoveredEmote.name());
        float scale = hoveredEmote == null ? 1.0F : 1.25F;
        int rimTop = this.wheel.centerY - this.wheel.outerRadius - (int) Math.ceil(this.wheel.lift);
        int titleBottom = Math.clamp(rimTop - 3, TITLE_TOP + 1, WheelGeometry.TITLE_BOTTOM);
        float centerY = (TITLE_TOP + titleBottom) / 2.0F;
        drawWrappedLabel(graphics, text, this.wheel.centerX, centerY, Math.max(1, this.width - 24), titleBottom - TITLE_TOP, scale, TITLE_COLOR);
    }

    private void drawWrappedLabel(GuiGraphicsExtractor graphics, Component text, float centerX, float centerY, int width, int height, float scale, int color) {
        int lineSpacing = this.font.lineHeight + 2;
        List<FormattedCharSequence> lines = this.font.split(text, Math.max(1, (int) (width / scale)));
        float textHeight = Math.max(1, lines.size()) * lineSpacing;
        if (textHeight * scale > height) {
            // Shrinking the text also allows more characters per line.
            scale = (float) Math.sqrt(scale * height / textHeight);
            lines = this.font.split(text, Math.max(1, (int) (width / scale)));
            scale = Math.min(scale, (float) height / (Math.max(1, lines.size()) * lineSpacing));
        }
        float y = centerY - ((lines.size() - 1) * lineSpacing + this.font.lineHeight) * scale / 2.0F;
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix().translate(centerX, y).scale(scale);
        for (int i = 0; i < lines.size(); i++) {
            graphics.centeredText(this.font, lines.get(i), 0, i * lineSpacing, color);
        }
        pose.popMatrix();
    }

    private void drawCenter(GuiGraphicsExtractor graphics) {
        boolean empty = this.emotes.isEmpty();
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
        int footerWidth = Math.max(8, this.width - 2 * (EDIT_BUTTON_WIDTH + 8));

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

        if (this.emotes.isEmpty()) {
            drawFooterHint(graphics, Component.translatable("screen.emote.wheel.footer.no_shortcuts"), footerTop, footerWidth, BODY_COLOR);
            return;
        }

        drawFooterHint(graphics, Component.translatable("screen.emote.wheel.footer.release_to_play", this.bindingLabel), footerTop, footerWidth, BODY_COLOR);
        drawFooterHint(graphics, Component.translatable("screen.emote.wheel.footer.close"), footerTop + 11, footerWidth, MUTED_COLOR);
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

    private EmoteSummary hoveredEmote() {
        int slot = this.wheel.selected();
        return slot < 0 ? null : this.pageEmotes.get(slot);
    }

    private void updateHoveredSlot(double mouseX, double mouseY) {
        int slot = this.wheel.slotAt(mouseX, mouseY, this.pageEmotes.size());
        if (this.wheel.select(slot, System.nanoTime()) && slot >= 0) {
            playWheelClick();
        }
    }

    private void playWheelClick() {
        this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.15F, 0.126F));
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
