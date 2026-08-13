package io.github.hanhy06.emote.client;

import io.github.hanhy06.emote.application.EmoteSummary;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;

import java.util.List;

@Environment(EnvType.CLIENT)
public class WheelShortcutScreen extends Screen {
    private static final int ENTRIES_PER_PAGE = WheelGeometry.SLOT_COUNT;
    private static final int ROW_WIDTH = 292;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_SPACING = 21;

    private final WheelController controller;
    private Tab tab = Tab.SELECTED;
    private int pageIndex;

    public WheelShortcutScreen(WheelController controller) {
        super(Component.translatable("screen.emote.shortcuts.title"));
        this.controller = controller;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int tabY = 28;
        Button selectedTab = this.addRenderableWidget(Button.builder(
            Component.translatable("screen.emote.shortcuts.selected"),
            ignoredButton -> changeTab(Tab.SELECTED)
        ).bounds(centerX - 146, tabY, 144, 20).build());
        Button availableTab = this.addRenderableWidget(Button.builder(
            Component.translatable("screen.emote.shortcuts.available"),
            ignoredButton -> changeTab(Tab.AVAILABLE)
        ).bounds(centerX + 2, tabY, 144, 20).build());
        selectedTab.active = this.tab != Tab.SELECTED;
        availableTab.active = this.tab != Tab.AVAILABLE;

        List<EmoteSummary> entries = getEntries();
        int pageCount = getPageCount(entries);
        this.pageIndex = Math.clamp(this.pageIndex, 0, pageCount - 1);
        int startIndex = Math.min(this.pageIndex * ENTRIES_PER_PAGE, entries.size());
        int endIndex = Math.min(startIndex + ENTRIES_PER_PAGE, entries.size());
        for (int entryIndex = startIndex; entryIndex < endIndex; entryIndex++) {
            int rowIndex = entryIndex - startIndex;
            int rowY = 62 + rowIndex * ROW_SPACING;
            EmoteSummary emote = entries.get(entryIndex);
            if (this.tab == Tab.SELECTED) {
                addSelectedRow(emote, entryIndex, rowY);
            } else {
                addAvailableRow(emote, rowY);
            }
        }

        int navigationY = this.height - 48;
        Button previousButton = this.addRenderableWidget(Button.builder(
            Component.translatable("screen.emote.shortcuts.previous"),
            ignoredButton -> changePage(-1)
        ).bounds(centerX - 146, navigationY, 80, 20).build());
        Button nextButton = this.addRenderableWidget(Button.builder(
            Component.translatable("screen.emote.shortcuts.next"),
            ignoredButton -> changePage(1)
        ).bounds(centerX + 66, navigationY, 80, 20).build());
        previousButton.active = this.pageIndex > 0;
        nextButton.active = this.pageIndex + 1 < pageCount;

        this.addRenderableWidget(Button.builder(
            Component.translatable("screen.emote.shortcuts.done"),
            ignoredButton -> this.onClose()
        ).bounds(centerX - 50, this.height - 24, 100, 20).build());
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
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title, this.width / 2, 10, 0xFFFFFFFF);

        List<EmoteSummary> entries = getEntries();
        if (entries.isEmpty()) {
            Component emptyMessage = Component.translatable(this.tab == Tab.SELECTED
                ? "screen.emote.shortcuts.empty_selected"
                : "screen.emote.shortcuts.empty_available");
            graphics.centeredText(this.font, emptyMessage, this.width / 2, 92, 0xFFB8C4CC);
        }

        if (this.tab == Tab.SELECTED) {
            graphics.centeredText(
                this.font,
                Component.translatable("screen.emote.shortcuts.selected_help"),
                this.width / 2,
                51,
                0xFFB8C4CC
            );
        }

        graphics.centeredText(
            this.font,
            Component.translatable("screen.emote.shortcuts.page", this.pageIndex + 1, getPageCount(entries)),
            this.width / 2,
            this.height - 42,
            0xFFFFFFFF
        );
    }

    private void addSelectedRow(EmoteSummary emote, int entryIndex, int rowY) {
        int rowX = (this.width - ROW_WIDTH) / 2;
        this.addRenderableWidget(new ShortcutOrderButton(
            rowX,
            rowY,
            234,
            ROW_HEIGHT,
            Component.literal("#" + (entryIndex + 1) + " " + emote.displayName()),
            () -> {
                this.controller.moveShortcutUp(emote.id());
                rebuildWidgets();
            },
            () -> {
                this.controller.moveShortcutDown(emote.id());
                rebuildWidgets();
            }
        ));
        this.addRenderableWidget(Button.builder(
            Component.translatable("screen.emote.shortcuts.remove"),
            ignoredButton -> {
                this.controller.removeShortcut(emote.id());
                rebuildWidgets();
            }
        ).bounds(rowX + 238, rowY, 54, ROW_HEIGHT).build());
    }

    private void addAvailableRow(EmoteSummary emote, int rowY) {
        int rowX = (this.width - ROW_WIDTH) / 2;
        this.addRenderableWidget(Button.builder(
            Component.translatable("screen.emote.shortcuts.add", emote.displayName()),
            ignoredButton -> {
                this.controller.addShortcut(emote.id());
                rebuildWidgets();
            }
        ).bounds(rowX, rowY, ROW_WIDTH, ROW_HEIGHT).build());
    }

    private List<EmoteSummary> getEntries() {
        return this.tab == Tab.SELECTED
            ? this.controller.getShortcutEmotes()
            : this.controller.getAvailableShortcutEmotes();
    }

    private int getPageCount(List<EmoteSummary> entries) {
        return Math.max(1, (entries.size() + ENTRIES_PER_PAGE - 1) / ENTRIES_PER_PAGE);
    }

    private void changeTab(Tab tab) {
        this.tab = tab;
        this.pageIndex = 0;
        rebuildWidgets();
    }

    private void changePage(int direction) {
        this.pageIndex = Math.clamp(this.pageIndex + direction, 0, getPageCount(getEntries()) - 1);
        rebuildWidgets();
    }

    private enum Tab {
        SELECTED,
        AVAILABLE
    }

    private static class ShortcutOrderButton extends Button.Plain {
        private final Runnable moveUp;
        private final Runnable moveDown;

        private ShortcutOrderButton(
            int x,
            int y,
            int width,
            int height,
            Component message,
            Runnable moveUp,
            Runnable moveDown
        ) {
            super(x, y, width, height, message, ignoredButton -> {}, Button.DEFAULT_NARRATION);
            this.moveUp = moveUp;
            this.moveDown = moveDown;
        }

        @Override
        public void onPress(InputWithModifiers input) {
            if (input instanceof MouseButtonEvent event && event.button() == 1) {
                this.moveDown.run();
            } else {
                this.moveUp.run();
            }
        }

        @Override
        protected boolean isValidClickButton(MouseButtonInfo buttonInfo) {
            return buttonInfo.button() == 0 || buttonInfo.button() == 1;
        }
    }
}
