package io.github.hanhy06.emote.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.application.EmoteSummary;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix3x2fStack;
import org.jspecify.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

final class WheelRenderer {
    private static final Identifier SLOT = Identifier.parse("emote:textures/gui/wheel/slot.png");
    private static final Identifier HUB = Identifier.parse("emote:textures/gui/wheel/hub.png");
    private static final Identifier INDICATOR = Identifier.parse("emote:textures/gui/wheel/indicator.png");
    private static final RenderPipeline WHEEL_SHAPE = RenderPipeline.builder()
        .withLocation(Identifier.parse("emote:pipeline/wheel_shape"))
        .withVertexShader("core/position_tex_color")
        .withFragmentShader(Identifier.parse("emote:core/wheel_shape"))
        .withUniform("Projection", UniformType.UNIFORM_BUFFER)
        .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
        .withSampler("Sampler0")
        .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
        .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
        .build();
    private static final String ELLIPSIS = "…";
    private static final int SLOT_FILL_COLOR = 0xF33B4752;
    private static final int SLOT_HIGHLIGHT_FILL_COLOR = 0xFF265B83;
    private static final int SLOT_EMPTY_FILL_COLOR = 0xC21C242B;
    private static final int HUB_COLOR = 0xFA171F27;
    private static final int INDICATOR_COLOR = 0xFF79BDEF;
    private static final int TITLE_COLOR = 0xFFF7FAFC;
    private static final int EMPTY_LABEL_COLOR = 0xFF9DB0BC;

    private WheelRenderer() {}

    static @Nullable BufferedImage loadSlotMask(ResourceProvider resources) {
        try (var input = resources.open(SLOT)) {
            return ImageIO.read(input);
        } catch (IOException exception) {
            EmoteMod.LOGGER.warn("Could not load the emote wheel slot mask", exception);
            return null;
        }
    }

    static void draw(GuiGraphicsExtractor graphics, Font font, WheelGeometry wheel, List<EmoteSummary> pageEmotes, long now, boolean editing) {
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix().translate(wheel.centerX, wheel.centerY);
        float progress = wheel.selectionProgress(now);
        for (int i = 0; i < WheelGeometry.SLOT_COUNT; i++) {
            float entrance = wheel.entrance(i, now);
            EmoteSummary emote = i < pageEmotes.size() ? pageEmotes.get(i) : null;
            boolean hasEmote = emote != null;
            float weight = hasEmote || editing ? wheel.weight(i, progress) : 0;
            float angle = (float) (i * WheelGeometry.SLOT_ANGLE);
            float scale = wheel.slotScale(entrance);
            float lift = wheel.slotLift(weight);
            int color = ARGB.srgbLerp(weight, hasEmote ? SLOT_FILL_COLOR : SLOT_EMPTY_FILL_COLOR, SLOT_HIGHLIGHT_FILL_COLOR);
            pose.pushMatrix().rotate(angle).translate(0, -lift).scale(scale);
            drawWheelPart(graphics, SLOT, WheelGeometry.SLOT_X, WheelGeometry.SLOT_Y, WheelGeometry.SLOT_WIDTH, WheelGeometry.SLOT_HEIGHT,
                ARGB.color(Math.round(ARGB.alpha(color) * entrance), color));
            pose.popMatrix();

            if ((hasEmote || editing) && entrance > 0) {
                float distance = WheelGeometry.LABEL_RADIUS * scale + lift;
                float x = (float) Math.sin(angle) * distance;
                float y = -(float) Math.cos(angle) * distance;
                drawSlotLabel(graphics, font, wheel, hasEmote ? emote.name() : "+", i == wheel.selected(), x, y,
                    ARGB.color(Math.round(255 * entrance), hasEmote ? TITLE_COLOR : EMPTY_LABEL_COLOR));
            }
        }

        pose.scale(wheel.scale);
        drawWheelPart(graphics, HUB, -40, -40, 80, 80, HUB_COLOR);
        float opacity = wheel.indicatorOpacity(progress);
        if (opacity > 0) {
            pose.rotate((float) wheel.indicatorAngle(now));
            drawWheelPart(graphics, INDICATOR, -17, -41, 34, 10, ARGB.color(Math.round(255 * opacity), INDICATOR_COLOR));
        }
        pose.popMatrix();
    }

    private static void drawWheelPart(GuiGraphicsExtractor graphics, Identifier texture, int x, int y, int width, int height, int color) {
        graphics.blit(WHEEL_SHAPE, texture, x, y, 0, 0, width, height, width, height, color);
    }

    private static void drawSlotLabel(GuiGraphicsExtractor graphics, Font font, WheelGeometry wheel, String name, boolean selected, float x, float y, int color) {
        int width = wheel.textWidth;
        String singleLine = name.replace('\n', ' ').replace('\r', ' ');
        boolean tooWide = font.width(singleLine) > width;
        if (selected && (tooWide || !singleLine.equals(name))) {
            int height = Math.max(4, Math.min(Math.round(wheel.outerRadius * 0.34F), 3 * (font.lineHeight + 2)));
            drawWrappedLabel(graphics, font, Component.literal(name), x, y, width, height, 1, color);
            return;
        }

        if (tooWide) {
            int textWidth = Math.max(0, width - font.width(ELLIPSIS));
            singleLine = font.plainSubstrByWidth(singleLine, textWidth).stripTrailing() + ELLIPSIS;
        }
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix().translate(x, y - font.lineHeight / 2.0F);
        graphics.centeredText(font, singleLine, 0, 0, color);
        pose.popMatrix();
    }

    static void drawWrappedLabel(GuiGraphicsExtractor graphics, Font font, Component text, float centerX, float centerY, int width, int height, float scale, int color) {
        int lineSpacing = font.lineHeight + 2;
        List<FormattedCharSequence> lines = font.split(text, Math.max(1, (int) (width / scale)));
        float textHeight = Math.max(1, lines.size()) * lineSpacing;
        if (textHeight * scale > height) {
            // Shrinking the text also allows more characters per line.
            scale = (float) Math.sqrt(scale * height / textHeight);
            lines = font.split(text, Math.max(1, (int) (width / scale)));
            scale = Math.min(scale, (float) height / (Math.max(1, lines.size()) * lineSpacing));
        }
        float y = centerY - ((lines.size() - 1) * lineSpacing + font.lineHeight) * scale / 2.0F;
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix().translate(centerX, y).scale(scale);
        for (int i = 0; i < lines.size(); i++) {
            graphics.centeredText(font, lines.get(i), 0, i * lineSpacing, color);
        }
        pose.popMatrix();
    }
}
