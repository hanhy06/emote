package io.github.hanhy06.emote.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.hanhy06.emote.application.EmoteSummary;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.SelectableEntry;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

import java.util.List;

@Environment(EnvType.CLIENT)
final class EmoteShortcutList extends ObjectSelectionList<EmoteShortcutList.Entry> {
    private static final Identifier UNSELECT_HIGHLIGHTED_SPRITE = Identifier.withDefaultNamespace("transferable_list/unselect_highlighted");
    private static final Identifier UNSELECT_SPRITE = Identifier.withDefaultNamespace("transferable_list/unselect");
    private static final int ENTRY_HEIGHT = 36;
    private static final int CONTROL_SIZE = 32;
    private static final int CONTROL_Y_OFFSET = -1;
    private static final int TEXT_GAP = 4;

    private final WheelShortcutScreen screen;

    EmoteShortcutList(
        Minecraft minecraft,
        WheelShortcutScreen screen,
        int x,
        int y,
        int width,
        int height
    ) {
        super(minecraft, width, height, y, ENTRY_HEIGHT);
        this.screen = screen;
        this.centerListVertically = false;
        updateSizeAndPosition(width, height, x, y);
    }

    static int fitHeight(int availableHeight) {
        int rowCount = Math.max(1, (availableHeight - 4) / ENTRY_HEIGHT);
        return rowCount * ENTRY_HEIGHT + 4;
    }

    @Override
    public int getRowWidth() {
        return this.width - 4;
    }

    @Override
    protected int scrollBarX() {
        return getRight() - scrollbarWidth();
    }

    void updateEntries(List<EmoteSummary> emotes) {
        double previousScroll = scrollAmount();
        clearEntries();
        for (EmoteSummary emote : emotes) {
            addEntry(new Entry(emote));
        }
        setScrollAmount(previousScroll);
        refreshScrollAmount();
    }

    boolean isEmpty() {
        return children().isEmpty();
    }

    final class Entry extends ObjectSelectionList.Entry<Entry> implements SelectableEntry {
        private final EmoteSummary emote;

        private Entry(EmoteSummary emote) {
            this.emote = emote;
        }

        @Override
        public int getWidth() {
            return super.getWidth() - (EmoteShortcutList.this.scrollable() ? EmoteShortcutList.this.scrollbarWidth() : 0);
        }

        @Override
        public @NonNull Component getNarration() {
            return Component.literal(this.emote.name());
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
            int controlX = getContentX();
            int controlY = getContentY() + CONTROL_Y_OFFSET;
            boolean showControls = hovered || EmoteShortcutList.this.getSelected() == this && EmoteShortcutList.this.isFocused();

            Identifier sprite = showControls ? UNSELECT_HIGHLIGHTED_SPRITE : UNSELECT_SPRITE;
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, controlX, controlY, CONTROL_SIZE, CONTROL_SIZE);

            int textX = controlX + CONTROL_SIZE + TEXT_GAP;
            int textWidth = Math.max(0, getContentRight() - textX - TEXT_GAP);
            graphics.text(minecraft.font, fitText(this.emote.name(), textWidth), textX, controlY + 3, 0xFFFFFFFF);
            graphics.text(minecraft.font, fitText(this.emote.description(), textWidth), textX, controlY + 16, 0xFF9DAAB3);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
                return super.mouseClicked(event, doubleClick);
            }
            screen.startDragFromAvailable(this.emote.id());
            return true;
        }

        private Component fitText(String text, int maxWidth) {
            if (minecraft.font.width(text) <= maxWidth) {
                return Component.literal(text);
            }

            String ellipsis = "...";
            return Component.literal(minecraft.font.plainSubstrByWidth(text, Math.max(0, maxWidth - minecraft.font.width(ellipsis))) + ellipsis);
        }
    }
}
