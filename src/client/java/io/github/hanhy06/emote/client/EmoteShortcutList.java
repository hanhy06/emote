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
    private static final Identifier MOVE_UP_HIGHLIGHTED_SPRITE = Identifier.withDefaultNamespace("transferable_list/move_up_highlighted");
    private static final Identifier MOVE_UP_SPRITE = Identifier.withDefaultNamespace("transferable_list/move_up");
    private static final Identifier MOVE_DOWN_HIGHLIGHTED_SPRITE = Identifier.withDefaultNamespace("transferable_list/move_down_highlighted");
    private static final Identifier MOVE_DOWN_SPRITE = Identifier.withDefaultNamespace("transferable_list/move_down");
    private static final int ENTRY_HEIGHT = 36;
    private static final int CONTROL_SIZE = 32;
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
        for (int index = 0; index < emotes.size(); index++) {
            addEntry(new Entry(emotes.get(index), index, emotes.size()));
        }
        setScrollAmount(previousScroll);
        refreshScrollAmount();
    }

    boolean isEmpty() {
        return children().isEmpty();
    }

    final class Entry extends ObjectSelectionList.Entry<Entry> implements SelectableEntry {
        private final EmoteSummary emote;
        private final int index;
        private final int entryCount;

        private Entry(EmoteSummary emote, int index, int entryCount) {
            this.emote = emote;
            this.index = index;
            this.entryCount = entryCount;
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
            int controlY = getContentY();
            boolean showControls = hovered || EmoteShortcutList.this.getSelected() == this && EmoteShortcutList.this.isFocused();

            graphics.fill(controlX, controlY, controlX + CONTROL_SIZE, controlY + CONTROL_SIZE, 0xFF303840);
            if (showControls) {
                extractControls(graphics, mouseX - controlX, mouseY - controlY, controlX, controlY);
            } else {
                Component marker = selectedList ? Component.literal(Integer.toString(this.index + 1)) : Component.literal("+");
                graphics.centeredText(minecraft.font, marker, controlX + CONTROL_SIZE / 2, controlY + 12, 0xFFE8EEF2);
            }

            int textX = controlX + CONTROL_SIZE + TEXT_GAP;
            int textWidth = Math.max(0, getContentRight() - textX - TEXT_GAP);
            graphics.text(minecraft.font, fitText(this.emote.displayName(), textWidth), textX, controlY + 3, 0xFFFFFFFF);
            graphics.text(minecraft.font, fitText(this.emote.description(), textWidth), textX, controlY + 16, 0xFF9DAAB3);
        }

        private void extractControls(GuiGraphicsExtractor graphics, int relX, int relY, int x, int y) {
            if (!selectedList) {
                Identifier sprite = isInside(relX, relY, 0, 0, CONTROL_SIZE, CONTROL_SIZE)
                    ? SELECT_HIGHLIGHTED_SPRITE
                    : SELECT_SPRITE;
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, CONTROL_SIZE, CONTROL_SIZE);
                return;
            }

            Identifier unselectSprite = isInside(relX, relY, 0, 0, CONTROL_SIZE / 2, CONTROL_SIZE)
                ? UNSELECT_HIGHLIGHTED_SPRITE
                : UNSELECT_SPRITE;
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, unselectSprite, x, y, CONTROL_SIZE, CONTROL_SIZE);
            if (this.index > 0) {
                Identifier upSprite = isInside(relX, relY, CONTROL_SIZE / 2, 0, CONTROL_SIZE / 2, CONTROL_SIZE / 2)
                    ? MOVE_UP_HIGHLIGHTED_SPRITE
                    : MOVE_UP_SPRITE;
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, upSprite, x, y, CONTROL_SIZE, CONTROL_SIZE);
            }
            if (this.index + 1 < this.entryCount) {
                Identifier downSprite = isInside(relX, relY, CONTROL_SIZE / 2, CONTROL_SIZE / 2, CONTROL_SIZE / 2, CONTROL_SIZE / 2)
                    ? MOVE_DOWN_HIGHLIGHTED_SPRITE
                    : MOVE_DOWN_SPRITE;
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, downSprite, x, y, CONTROL_SIZE, CONTROL_SIZE);
            }
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            int relX = (int) event.x() - getContentX();
            int relY = (int) event.y() - getContentY();
            if (!isInside(relX, relY, 0, 0, CONTROL_SIZE, CONTROL_SIZE)) {
                return super.mouseClicked(event, doubleClick);
            }

            if (!selectedList) {
                controller.addShortcut(this.emote.id());
            } else if (relX < CONTROL_SIZE / 2) {
                controller.removeShortcut(this.emote.id());
            } else if (relY < CONTROL_SIZE / 2 && this.index > 0) {
                controller.moveShortcutUp(this.emote.id());
            } else if (relY >= CONTROL_SIZE / 2 && this.index + 1 < this.entryCount) {
                controller.moveShortcutDown(this.emote.id());
            } else {
                return true;
            }
            screen.refreshLists();
            return true;
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
            if (selectedList && event.hasShiftDown() && event.isUp() && this.index > 0) {
                controller.moveShortcutUp(this.emote.id());
                screen.refreshLists();
                return true;
            }
            if (selectedList && event.hasShiftDown() && event.isDown() && this.index + 1 < this.entryCount) {
                controller.moveShortcutDown(this.emote.id());
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
