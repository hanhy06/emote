package io.github.hanhy06.emote.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.hanhy06.emote.application.EmoteQueryService;
import io.github.hanhy06.emote.application.EmoteSummary;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.SelectableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.List;

@Environment(EnvType.CLIENT)
final class WheelSearchScreen extends Screen {
    private static final int LIST_TOP = 48;
    private static final int ROW_HEIGHT = 28;

    private final WheelController controller;
    private final List<EmoteSummary> emotes;
    private InputConstants.@Nullable Key pendingWheelKey;
    private EditBox searchBox;
    private Results results;

    WheelSearchScreen(WheelController controller, List<EmoteSummary> emotes, KeyMapping wheelKeyMapping) {
        super(Component.translatable("screen.emote.wheel.search"));
        this.controller = controller;
        this.emotes = emotes;
        InputConstants.Key wheelKey = KeyMappingHelper.getBoundKeyOf(wheelKeyMapping);
        if (wheelKey.getType() == InputConstants.Type.KEYBOARD && InputConstants.isKeyDown(wheelKey.getValue())) {
            this.pendingWheelKey = wheelKey;
        }
    }

    @Override
    protected void init() {
        int listWidth = Math.min(300, this.width - 24);
        int listX = (this.width - listWidth) / 2;
        Component hint = Component.translatable("screen.emote.shortcuts.search");
        this.searchBox = this.addRenderableWidget(new EditBox(this.font, listX, 22, listWidth, 20, this.searchBox, hint));
        this.searchBox.setMaxLength(Integer.MAX_VALUE);
        this.searchBox.setHint(hint.copy().setStyle(EditBox.SEARCH_HINT_STYLE));
        this.results = this.addRenderableWidget(new Results(listX, listWidth, Math.max(ROW_HEIGHT, this.height - LIST_TOP - 32)));
        this.searchBox.setResponder(this.results::updateEntries);
        this.results.updateEntries(this.searchBox.getValue());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, ignoredButton -> this.onClose())
            .bounds(this.width / 2 - 50, this.height - 24, 100, 20).build());
    }

    @Override
    protected void setInitialFocus() {
        setInitialFocus(this.searchBox);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return (this.pendingWheelKey != null && this.pendingWheelKey.getValue() == event.key()) || super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (this.pendingWheelKey != null && this.pendingWheelKey.getValue() == event.key()) {
            this.pendingWheelKey = null;
            return true;
        }
        return super.keyReleased(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        // Character events carry only the code point, including events from key repeats.
        return this.pendingWheelKey != null || super.charTyped(event);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.pendingWheelKey != null && !InputConstants.isKeyDown(this.pendingWheelKey.getValue())) {
            this.pendingWheelKey = null;
        }
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
        graphics.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);
        if (this.results.children().isEmpty()) {
            graphics.centeredText(this.font, Component.translatable("screen.emote.shortcuts.empty_search"),
                this.width / 2, LIST_TOP + 12, 0xFFB8C4CC);
        }
    }

    private final class Results extends ObjectSelectionList<Result> {
        private Results(int x, int width, int height) {
            super(WheelSearchScreen.this.minecraft, width, height, LIST_TOP, ROW_HEIGHT);
            this.centerListVertically = false;
            updateSizeAndPosition(width, height, x, LIST_TOP);
        }

        @Override
        public int getRowWidth() {
            return this.width - 16;
        }

        @Override
        protected int scrollBarX() {
            return getRight() - scrollbarWidth();
        }

        private void updateEntries(String query) {
            clearEntries();
            for (EmoteSummary emote : EmoteQueryService.filterPreservingOrder(emotes, query)) {
                addEntry(new Result(emote));
            }
            setScrollAmount(0);
            refreshScrollAmount();
        }
    }

    private final class Result extends ObjectSelectionList.Entry<Result> implements SelectableEntry {
        private final EmoteSummary emote;

        private Result(EmoteSummary emote) {
            this.emote = emote;
        }

        @Override
        public Component getNarration() {
            return Component.literal(this.emote.name());
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, boolean hovered) {
            int textWidth = getContentWidth();
            graphics.text(font, font.plainSubstrByWidth(this.emote.name(), textWidth), getContentX(), getContentY() + 2, 0xFFFFFFFF);
            graphics.text(font, font.plainSubstrByWidth(this.emote.description(), textWidth), getContentX(), getContentY() + 14, 0xFF9DAAB3);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
                return false;
            }
            play();
            return true;
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (!event.isConfirmation()) {
                return super.keyPressed(event);
            }
            play();
            return true;
        }

        private void play() {
            AbstractWidget.playButtonClickSound(minecraft.getSoundManager());
            onClose();
            controller.play(this.emote);
        }
    }
}
