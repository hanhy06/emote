package io.github.hanhy06.emote.client;

import io.github.hanhy06.emote.application.EmoteSummary;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.SelectableEntry;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;

@Environment(EnvType.CLIENT)
final class EmoteShortcutList extends ObjectSelectionList<EmoteShortcutList.Entry> {
    private static final Identifier SELECT_HIGHLIGHTED_SPRITE = Identifier.withDefaultNamespace("transferable_list/select_highlighted");
    private static final Identifier SELECT_SPRITE = Identifier.withDefaultNamespace("transferable_list/select");
    private static final Identifier UNSELECT_HIGHLIGHTED_SPRITE = Identifier.withDefaultNamespace("transferable_list/unselect_highlighted");
    private static final Identifier UNSELECT_SPRITE = Identifier.withDefaultNamespace("transferable_list/unselect");
    private static final int ENTRY_HEIGHT = 36;
    private static final int CONTROL_SIZE = 32;
    private static final int CONTROL_Y_OFFSET = -1;
    private static final int TEXT_GAP = 4;

    private final WheelShortcutScreen screen;
    private final WheelController controller;
    private final boolean selectedList;

    EmoteShortcutList(
        Minecraft minecraft,
        WheelShortcutScreen screen,
        WheelController controller,
        boolean selectedList,
        int x,
        int y,
        int width,
        int height
    ) {
        super(minecraft, width, height, y, ENTRY_HEIGHT);
        this.screen = screen;
        this.controller = controller;
        this.selectedList = selectedList;
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

    @Override
    public boolean keyPressed(KeyEvent event) {
        return getSelected() != null ? getSelected().keyPressed(event) : super.keyPressed(event);
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

    private void dragEntry(Entry entry, double mouseY) {
        int currentIndex = children().indexOf(entry);
        if (!this.selectedList || currentIndex < 0) {
            return;
        }

        if (mouseY < getY() + 12) {
            setScrollAmount(scrollAmount() - 4);
        } else if (mouseY > getBottom() - 12) {
            setScrollAmount(scrollAmount() + 4);
        }

        int targetIndex = Math.clamp(
            (int) ((mouseY - getY() - 2 + scrollAmount()) / ENTRY_HEIGHT),
            0,
            children().size() - 1
        );
        if (currentIndex == targetIndex) {
            return;
        }

        this.controller.moveShortcutTo(entry.emote.id(), targetIndex);
        while (currentIndex < targetIndex) {
            swap(currentIndex, currentIndex + 1);
            currentIndex++;
        }
        while (currentIndex > targetIndex) {
            swap(currentIndex, currentIndex - 1);
            currentIndex--;
        }
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
        public Component getNarration() {
            return Component.literal(this.emote.displayName());
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            int controlX = getContentX();
            int controlY = getContentY() + CONTROL_Y_OFFSET;
            boolean showControls = hovered || EmoteShortcutList.this.getSelected() == this && EmoteShortcutList.this.isFocused();

            extractTransferArrow(graphics, showControls, controlX, controlY);

            int textX = controlX + CONTROL_SIZE + TEXT_GAP;
            int textWidth = Math.max(0, getContentRight() - textX - TEXT_GAP);
            graphics.text(minecraft.font, fitText(this.emote.displayName(), textWidth), textX, controlY + 3, 0xFFFFFFFF);
            graphics.text(minecraft.font, fitText(this.emote.description(), textWidth), textX, controlY + 16, 0xFF9DAAB3);
        }

        private void extractTransferArrow(GuiGraphicsExtractor graphics, boolean highlighted, int x, int y) {
            Identifier sprite = selectedList
                ? highlighted ? SELECT_HIGHLIGHTED_SPRITE : SELECT_SPRITE
                : highlighted ? UNSELECT_HIGHLIGHTED_SPRITE : UNSELECT_SPRITE;
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, CONTROL_SIZE, CONTROL_SIZE);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            int relX = (int) event.x() - getContentX();
            int relY = (int) event.y() - (getContentY() + CONTROL_Y_OFFSET);
            if (!isInside(relX, relY, 0, 0, CONTROL_SIZE, CONTROL_SIZE)) {
                return super.mouseClicked(event, doubleClick);
            }

            if (!selectedList) {
                controller.addShortcut(this.emote.id());
            } else {
                controller.removeShortcut(this.emote.id());
            }
            screen.refreshLists();
            return true;
        }

        @Override
        public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
            if (selectedList && event.button() == 0) {
                EmoteShortcutList.this.dragEntry(this, event.y());
                return true;
            }
            return super.mouseDragged(event, dx, dy);
        }

        @Override
        public boolean shouldTakeFocusAfterInteraction() {
            return EmoteShortcutList.this.children().contains(this);
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (event.isConfirmation()) {
                if (selectedList) {
                    controller.removeShortcut(this.emote.id());
                } else {
                    controller.addShortcut(this.emote.id());
                }
                screen.refreshLists();
                return true;
            }
            return super.keyPressed(event);
        }

        private Component fitText(String text, int maxWidth) {
            if (minecraft.font.width(text) <= maxWidth) {
                return Component.literal(text);
            }

            String ellipsis = "...";
            return Component.literal(minecraft.font.plainSubstrByWidth(text, Math.max(0, maxWidth - minecraft.font.width(ellipsis))) + ellipsis);
        }

        private boolean isInside(int x, int y, int areaX, int areaY, int width, int height) {
            return x >= areaX && x < areaX + width && y >= areaY && y < areaY + height;
        }
    }
}
