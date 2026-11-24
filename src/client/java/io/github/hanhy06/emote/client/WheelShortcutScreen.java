package io.github.hanhy06.emote.client;

import io.github.hanhy06.emote.application.EmoteQueryService;
import io.github.hanhy06.emote.application.EmoteSummary;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.ScrollWheelHandler;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
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
    private static final int MUTED_COLOR = 0xFFB8C4CC;

    private final WheelShortcutSettings shortcuts;
    private final List<String> slotIds;
    private final ScrollWheelHandler pageScroll = new ScrollWheelHandler();
    private int pageIndex;
    private List<EmoteSummary> pageEmotes;
    private WheelGeometry wheel;
    private EmoteShortcutList availableList;
    private EditBox searchBox;
    private DraggedShortcut dragged;

    WheelShortcutScreen(WheelShortcutSettings shortcuts, int pageIndex) {
        super(Component.translatable("screen.emote.shortcuts.title"));
        this.shortcuts = shortcuts;
        this.slotIds = new ArrayList<>(shortcuts.slotIds());
        this.pageIndex = pageIndex;
        refreshPage();
    }

    @Override
    protected void init() {
        int listWidth = Math.clamp((this.width - LIST_GAP - 16) / 2, 24, LIST_MAX_WIDTH);
        int panelHeight = Math.max(40, this.height - LIST_TOP - FOOTER_HEIGHT);
        int listHeight = EmoteShortcutList.fitHeight(panelHeight);
        int centerX = this.width / 2;
        int wheelX = centerX - LIST_GAP / 2 - listWidth;
        int reservedHeight = 31;
        int radius = Math.max(4, Math.min((listWidth - 12) / 2, (panelHeight - reservedHeight - 10) / 2));
        var slotMask = WheelRenderer.loadSlotMask(this.minecraft.getResourceManager());
        this.wheel = new WheelGeometry(wheelX + listWidth / 2, LIST_TOP + (panelHeight - reservedHeight) / 2, radius, System.nanoTime(), slotMask);
        this.dragged = null;

        int sortButtonWidth = (listWidth - BUTTON_GAP) / 2;
        this.addRenderableWidget(Button.builder(
            Component.translatable("screen.emote.shortcuts.sort_name"), ignoredButton -> sortShortcuts(false)
        ).bounds(wheelX, TOOLBAR_TOP, sortButtonWidth, TOOLBAR_HEIGHT).build());
        this.addRenderableWidget(Button.builder(
            Component.translatable("screen.emote.shortcuts.sort_tags"), ignoredButton -> sortShortcuts(true)
        ).bounds(wheelX + sortButtonWidth + BUTTON_GAP, TOOLBAR_TOP, listWidth - sortButtonWidth - BUTTON_GAP, TOOLBAR_HEIGHT).build());

        int listX = centerX + LIST_GAP / 2;
        this.searchBox = this.addRenderableWidget(new EditBox(
            this.font, listX, TOOLBAR_TOP, listWidth, TOOLBAR_HEIGHT, this.searchBox,
            Component.translatable("screen.emote.shortcuts.search")
        ));
        this.searchBox.setMaxLength(Integer.MAX_VALUE);
        this.searchBox.setHint(Component.translatable("screen.emote.shortcuts.search").setStyle(EditBox.SEARCH_HINT_STYLE));
        this.availableList = this.addRenderableWidget(new EmoteShortcutList(this.minecraft, this, listX, LIST_TOP, listWidth, listHeight));
        this.searchBox.setResponder(this::refreshAvailableList);
        refreshAvailableList(this.searchBox.getValue());

        int footerButtonWidth = Math.min(BUTTON_WIDTH, listWidth);
        this.addRenderableWidget(Button.builder(
            CommonComponents.GUI_CANCEL, ignoredButton -> this.onClose()
        ).bounds(centerX - BUTTON_GAP / 2 - footerButtonWidth, this.height - 24, footerButtonWidth, 20).build());
        this.addRenderableWidget(Button.builder(
            CommonComponents.GUI_DONE, ignoredButton -> {
                this.shortcuts.saveSlots(this.slotIds);
                this.onClose();
            }
        ).bounds(centerX + BUTTON_GAP / 2, this.height - 24, footerButtonWidth, 20).build());
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
        long now = System.nanoTime();
        this.wheel.select(this.wheel.hoveredSlotAt(graphics.mouseX(), graphics.mouseY(), now), now);
        WheelRenderer.draw(graphics, this.font, this.wheel, this.pageEmotes, now, true);

        int listWidth = this.availableList.getWidth();
        int availableCenterX = this.availableList.getX() + listWidth / 2;
        graphics.centeredText(this.font, this.title, this.width / 2, 10, 0xFFFFFFFF);
        graphics.centeredText(this.font, Component.translatable("screen.emote.shortcuts.selected"), this.wheel.centerX, LIST_LABEL_TOP, 0xFFFFFFFF);
        graphics.centeredText(this.font, Component.translatable("screen.emote.shortcuts.available"), availableCenterX, LIST_LABEL_TOP, 0xFFFFFFFF);

        int pageY = this.wheel.centerY + this.wheel.outerRadius + 8;
        int pageCount = Math.max(this.pageIndex + 1, Math.ceilDiv(this.slotIds.size(), WheelGeometry.SLOT_COUNT));
        graphics.centeredText(this.font, Component.translatable("screen.emote.shortcuts.page", this.pageIndex + 1, pageCount),
            this.wheel.centerX, pageY, MUTED_COLOR);
        WheelRenderer.drawWrappedLabel(graphics, this.font, Component.translatable("screen.emote.shortcuts.wheel_help"),
            this.wheel.centerX, pageY + 22, listWidth, 22, 1, MUTED_COLOR);

        if (this.availableList.isEmpty()) {
            Component message = Component.translatable(this.searchBox.getValue().isBlank()
                ? "screen.emote.shortcuts.empty_available" : "screen.emote.shortcuts.empty_search");
            graphics.centeredText(this.font, fitText(message, listWidth - 12), availableCenterX, LIST_TOP + 18, MUTED_COLOR);
        }
        if (this.dragged != null) {
            EmoteSummary emote = this.shortcuts.emote(this.dragged.id());
            if (emote != null) {
                drawDraggedShortcut(graphics, emote);
            }
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (this.dragged != null) {
            return true;
        }
        int slot = slotAt(event.x(), event.y());
        String id = idAt(slot);
        if (this.shortcuts.emote(id) != null) {
            if (event.isLeftMouse()) {
                this.dragged = new DraggedShortcut(id, slot);
                return true;
            }
            if (event.isRightMouse()) {
                this.slotIds.set(slot, null);
                refreshLayout();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        return (event.isLeftMouse() && this.dragged != null) || super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        boolean handled = super.mouseReleased(event);
        if (!event.isLeftMouse() || this.dragged == null) {
            return handled;
        }

        DraggedShortcut shortcut = this.dragged;
        this.dragged = null;
        int targetSlot = slotAt(event.x(), event.y());
        boolean overList = targetSlot < 0 && this.availableList.isMouseOver(event.x(), event.y());
        if (dropShortcut(shortcut, targetSlot, overList)) {
            refreshLayout();
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        int halfWidth = this.availableList.getWidth() / 2;
        if (x < this.wheel.centerX - halfWidth || x > this.wheel.centerX + halfWidth
            || y < LIST_TOP || y >= this.height - FOOTER_HEIGHT) {
            return super.mouseScrolled(x, y, scrollX, scrollY);
        }

        int direction = Integer.signum(this.pageScroll.onMouseScroll(0, scrollY).y);
        int nextPage = Math.clamp(this.pageIndex - direction, 0, Integer.MAX_VALUE / WheelGeometry.SLOT_COUNT - 1);
        if (this.pageIndex != nextPage) {
            this.pageIndex = nextPage;
            refreshPage();
        }
        return true;
    }

    void startDragFromAvailable(String id) {
        this.dragged = new DraggedShortcut(id, -1);
    }

    void refreshCatalog(int previousSlotCount) {
        List<String> savedIds = this.shortcuts.slotIds();
        this.slotIds.addAll(savedIds.subList(previousSlotCount, savedIds.size()));
        refreshLayout();
    }

    private boolean dropShortcut(DraggedShortcut shortcut, int targetSlot, boolean overList) {
        int sourceSlot = shortcut.sourceSlot();
        String id = shortcut.id();
        if (targetSlot == sourceSlot || (targetSlot < 0 && !overList) || this.shortcuts.emote(id) == null) {
            return false;
        }

        if (sourceSlot >= 0) {
            if (!id.equals(idAt(sourceSlot))) {
                return false;
            }
            String targetId = idAt(targetSlot);
            this.slotIds.set(sourceSlot, this.shortcuts.emote(targetId) == null ? null : targetId);
        }
        if (targetSlot >= 0) {
            while (this.slotIds.size() <= targetSlot) {
                this.slotIds.add(null);
            }
            this.slotIds.set(targetSlot, id);
        }
        return true;
    }

    private int slotAt(double x, double y) {
        int slot = this.wheel.slotAt(x, y, System.nanoTime());
        return slot < 0 ? -1 : this.pageIndex * WheelGeometry.SLOT_COUNT + slot;
    }

    private String idAt(int slot) {
        return slot >= 0 && slot < this.slotIds.size() ? this.slotIds.get(slot) : null;
    }

    private void refreshLayout() {
        refreshPage();
        refreshAvailableList(this.searchBox.getValue());
    }

    private void refreshPage() {
        this.pageEmotes = this.shortcuts.resolveSlots(WheelGeometry.page(this.slotIds, this.pageIndex));
    }

    private void refreshAvailableList(String query) {
        this.availableList.updateEntries(EmoteQueryService.filterPreservingOrder(this.shortcuts.availableEmotes(this.slotIds), query));
    }

    private void sortShortcuts(boolean groupByTag) {
        this.shortcuts.sortSlots(this.slotIds, groupByTag);
        refreshPage();
    }

    private void drawDraggedShortcut(GuiGraphicsExtractor graphics, EmoteSummary emote) {
        Component label = fitText(Component.literal(emote.name().replace('\n', ' ').replace('\r', ' ')), Math.min(132, this.width - 12));
        int width = this.font.width(label) + 8;
        int x = Math.clamp(graphics.mouseX() + 9, 0, Math.max(0, this.width - width));
        int y = Math.clamp(graphics.mouseY() + 9, 0, Math.max(0, this.height - 16));
        graphics.nextStratum();
        graphics.fill(x, y, x + width, y + 16, 0xF3265B83);
        graphics.text(this.font, label, x + 4, y + 4, 0xFFFFFFFF, true);
    }

    private Component fitText(Component text, int maxWidth) {
        String textValue = text.getString();
        if (this.font.width(textValue) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        return Component.literal(this.font.plainSubstrByWidth(textValue, Math.max(0, maxWidth - this.font.width(ellipsis))) + ellipsis);
    }

    private record DraggedShortcut(String id, int sourceSlot) {}
}
