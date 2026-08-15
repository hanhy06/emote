package io.github.hanhy06.emote.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

@Environment(EnvType.CLIENT)
public class WheelShortcutScreen extends Screen {
    private static final int LIST_MAX_WIDTH = 200;
    private static final int LIST_GAP = 30;
    private static final int LIST_TOP = 42;
    private static final int FOOTER_HEIGHT = 32;

    private final WheelController controller;
    private EmoteShortcutList selectedList;
    private EmoteShortcutList availableList;

    public WheelShortcutScreen(WheelController controller) {
        super(Component.translatable("screen.emote.shortcuts.title"));
        this.controller = controller;
    }

    @Override
    protected void init() {
        int listWidth = Math.min(LIST_MAX_WIDTH, Math.max(100, (this.width - LIST_GAP) / 2));
        int listHeight = Math.max(40, this.height - LIST_TOP - FOOTER_HEIGHT);
        int centerX = this.width / 2;

        this.selectedList = this.addRenderableWidget(new EmoteShortcutList(
            this.minecraft,
            this,
            this.controller,
            true,
            centerX - LIST_GAP / 2 - listWidth,
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
        refreshLists();

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
        int centerX = this.width / 2;
        int selectedCenterX = this.selectedList.getX() + this.selectedList.getWidth() / 2;
        int availableCenterX = this.availableList.getX() + this.availableList.getWidth() / 2;

        graphics.centeredText(this.font, this.title, centerX, 10, 0xFFFFFFFF);
        graphics.centeredText(this.font, Component.translatable("screen.emote.shortcuts.selected"), selectedCenterX, 28, 0xFFFFFFFF);
        graphics.centeredText(this.font, Component.translatable("screen.emote.shortcuts.available"), availableCenterX, 28, 0xFFFFFFFF);

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
                fitText(Component.translatable("screen.emote.shortcuts.empty_available"), this.availableList.getWidth() - 12),
                availableCenterX,
                LIST_TOP + 18,
                0xFFB8C4CC
            );
        }
    }

    void refreshLists() {
        this.selectedList.updateEntries(this.controller.getShortcutEmotes());
        this.availableList.updateEntries(this.controller.getAvailableShortcutEmotes());
    }

    private Component fitText(Component text, int maxWidth) {
        String value = text.getString();
        if (this.font.width(value) <= maxWidth) {
            return text;
        }

        String ellipsis = "...";
        return Component.literal(this.font.plainSubstrByWidth(value, Math.max(0, maxWidth - this.font.width(ellipsis))) + ellipsis);
    }
}
