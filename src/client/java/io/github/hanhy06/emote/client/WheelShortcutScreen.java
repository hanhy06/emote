package io.github.hanhy06.emote.client;

import io.github.hanhy06.emote.application.EmoteQueryService;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;

@Environment(EnvType.CLIENT)
public class WheelShortcutScreen extends Screen {
    private static final int LIST_MAX_WIDTH = 200;
    private static final int LIST_GAP = 8;
    private static final int TOOLBAR_TOP = 22;
    private static final int TOOLBAR_HEIGHT = 20;
    private static final int LIST_LABEL_TOP = 48;
    private static final int LIST_TOP = 62;
    private static final int FOOTER_HEIGHT = 32;
    private static final int BUTTON_WIDTH = 150;
    private static final int BUTTON_GAP = 8;

    private final WheelController controller;
    private final List<String> initialShortcutIds;
    private EmoteShortcutList selectedList;
    private EmoteShortcutList availableList;
    private EditBox searchBox;
    private boolean accepted;

    public WheelShortcutScreen(WheelController controller) {
        super(Component.translatable("screen.emote.shortcuts.title"));
        this.controller = controller;
        this.initialShortcutIds = List.copyOf(controller.shortcuts().selectedIds());
    }

    @Override
    protected void init() {
        int listWidth = Math.clamp((this.width - LIST_GAP) / 2, 100, LIST_MAX_WIDTH);
        int listHeight = EmoteShortcutList.fitHeight(Math.max(40, this.height - LIST_TOP - FOOTER_HEIGHT));
        int centerX = this.width / 2;
        int selectedListX = centerX - LIST_GAP / 2 - listWidth;
        int sortButtonWidth = (listWidth - BUTTON_GAP) / 2;

        this.addRenderableWidget(Button.builder(
            Component.translatable("screen.emote.shortcuts.sort_name"), ignoredButton -> sortShortcuts(false)
        ).bounds(selectedListX, TOOLBAR_TOP, sortButtonWidth, TOOLBAR_HEIGHT).build());
        this.addRenderableWidget(Button.builder(
            Component.translatable("screen.emote.shortcuts.sort_tags"), ignoredButton -> sortShortcuts(true)
        ).bounds(selectedListX + sortButtonWidth + BUTTON_GAP, TOOLBAR_TOP, listWidth - sortButtonWidth - BUTTON_GAP, TOOLBAR_HEIGHT).build());

        this.searchBox = this.addRenderableWidget(new EditBox(
            this.font,
            centerX + LIST_GAP / 2,
            TOOLBAR_TOP,
            listWidth,
            TOOLBAR_HEIGHT,
            this.searchBox,
            Component.translatable("screen.emote.shortcuts.search")
        ));
        this.searchBox.setMaxLength(Integer.MAX_VALUE);
        this.searchBox.setHint(Component.translatable("screen.emote.shortcuts.search").setStyle(EditBox.SEARCH_HINT_STYLE));

        this.selectedList = this.addRenderableWidget(new EmoteShortcutList(
            this.minecraft,
            this,
            this.controller,
            true,
            selectedListX,
            LIST_TOP,
            listWidth,
            listHeight
        ));
        this.availableList = this.addRenderableWidget(new EmoteShortcutList(
            this.minecraft,
            this,
            this.controller,
            false,
            centerX + LIST_GAP / 2,
            LIST_TOP,
            listWidth,
            listHeight
        ));
        this.searchBox.setResponder(this::refreshAvailableList);
        refreshLists();

        this.addRenderableWidget(Button.builder(
            CommonComponents.GUI_CANCEL,
            ignoredButton -> this.onClose()
        ).bounds(centerX - BUTTON_GAP / 2 - BUTTON_WIDTH, this.height - 24, BUTTON_WIDTH, 20).build());
        this.addRenderableWidget(Button.builder(
            CommonComponents.GUI_DONE,
            ignoredButton -> {
                this.accepted = true;
                this.onClose();
            }
        ).bounds(centerX + BUTTON_GAP / 2, this.height - 24, BUTTON_WIDTH, 20).build());
    }

    @Override
    public void onClose() {
        if (!this.accepted) {
            this.controller.shortcuts().updateSelectedIds(this.initialShortcutIds);
        }
        super.onClose();
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
    public void extractRenderState(GuiGraphicsExtractor graphics) {
        super.extractRenderState(graphics);
        int centerX = this.width / 2;
        int selectedCenterX = this.selectedList.getX() + this.selectedList.getWidth() / 2;
        int availableCenterX = this.availableList.getX() + this.availableList.getWidth() / 2;

        graphics.centeredText(this.font, this.title, centerX, 10, 0xFFFFFFFF);
        graphics.centeredText(this.font, Component.translatable("screen.emote.shortcuts.selected"), selectedCenterX, LIST_LABEL_TOP, 0xFFFFFFFF);
        graphics.centeredText(this.font, Component.translatable("screen.emote.shortcuts.available"), availableCenterX, LIST_LABEL_TOP, 0xFFFFFFFF);

        if (this.selectedList.isEmpty()) {
            graphics.centeredText(
                this.font,
                fitText(Component.translatable("screen.emote.shortcuts.empty_selected"), this.selectedList.getWidth() - 12),
                selectedCenterX,
                LIST_TOP + 18,
                0xFFB8C4CC
            );
        }
        if (this.availableList.isEmpty()) {
            graphics.centeredText(
                this.font,
                fitText(Component.translatable(
                    this.searchBox.getValue().isBlank()
                        ? "screen.emote.shortcuts.empty_available"
                        : "screen.emote.shortcuts.empty_search"
                ), this.availableList.getWidth() - 12),
                availableCenterX,
                LIST_TOP + 18,
                0xFFB8C4CC
            );
        }
    }

    void refreshLists() {
        this.selectedList.updateEntries(this.controller.shortcuts().selectedEmotes());
        refreshAvailableList(this.searchBox.getValue());
    }

    private void refreshAvailableList(String query) {
        this.availableList.updateEntries(EmoteQueryService.filterPreservingOrder(
            this.controller.shortcuts().availableEmotes(),
            query
        ));
    }

    private void sortShortcuts(boolean groupByTag) {
        this.controller.shortcuts().sortSelected(groupByTag);
        this.selectedList.updateEntries(this.controller.shortcuts().selectedEmotes());
        this.selectedList.setScrollAmount(0);
    }

    private Component fitText(Component text, int maxWidth) {
        String textValue = text.getString();
        if (this.font.width(textValue) <= maxWidth) {
            return text;
        }

        String ellipsis = "...";
        return Component.literal(this.font.plainSubstrByWidth(textValue, Math.max(0, maxWidth - this.font.width(ellipsis))) + ellipsis);
    }
}
