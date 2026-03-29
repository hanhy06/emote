package io.github.hanhy06.emote.client;

import io.github.hanhy06.emote.emote.PlayableEmote;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.util.Arrays;
import java.util.List;

@Environment(EnvType.CLIENT)
public class WheelScreen extends Screen {
	static final int SLOT_COUNT = 6;
	private static final int LEFT_MOUSE_BUTTON = 0;
	private static final int RIGHT_MOUSE_BUTTON = 1;
	private static final int BACKGROUND_TOP_COLOR = 0x7A101A22;
	private static final int BACKGROUND_BOTTOM_COLOR = 0xAD091117;
	private static final int SLOT_BORDER_COLOR = 0xFFA9C7D8;
	private static final int SLOT_FILL_COLOR = 0xD0223240;
	private static final int SLOT_HIGHLIGHT_FILL_COLOR = 0xF0517A94;
	private static final int SLOT_EMPTY_FILL_COLOR = 0x7F1A2530;
	private static final int CENTER_BORDER_COLOR = 0xFFDFE7EE;
	private static final int CENTER_FILL_COLOR = 0xD018242F;
	private static final int TITLE_COLOR = 0xFFF7FAFC;
	private static final int BODY_COLOR = 0xFFD1D9DF;
	private static final int MUTED_COLOR = 0xFF9DB0BC;
	private final WheelController controller;
	private final List<PlayableEmote> emotes;
	private final Component bindingLabel;
	private int pageIndex;
	private double lastMouseX;
	private double lastMouseY;
	private int hoveredSlotIndex = -1;

	public WheelScreen(WheelController controller, List<PlayableEmote> emotes, int pageIndex, Component bindingLabel) {
		super(Component.translatable("screen.emote.wheel.title"));
		this.controller = controller;
		this.emotes = List.copyOf(emotes);
		this.pageIndex = clampPageIndex(pageIndex);
		this.bindingLabel = bindingLabel;
	}

	@Override
	protected void init() {
		this.lastMouseX = this.width / 2.0D;
		this.lastMouseY = this.height / 2.0D;
		updateHoveredSlot(this.lastMouseX, this.lastMouseY);
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
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		graphics.fillGradient(0, 0, this.width, this.height, BACKGROUND_TOP_COLOR, BACKGROUND_BOTTOM_COLOR);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		this.lastMouseX = mouseX;
		this.lastMouseY = mouseY;
		updateHoveredSlot(mouseX, mouseY);

		WheelMetrics metrics = createMetrics();
		List<PlayableEmote> pageEmotes = getCurrentPageEmotes();

		graphics.centeredText(this.font, this.title, metrics.centerX(), 18, TITLE_COLOR);

		for (int slotIndex = 0; slotIndex < SLOT_COUNT; slotIndex++) {
			SlotGeometry slot = createSlotGeometry(slotIndex, metrics);
			PlayableEmote playableEmote = slotIndex < pageEmotes.size() ? pageEmotes.get(slotIndex) : null;
			boolean hovered = slotIndex == this.hoveredSlotIndex;
			drawSlot(graphics, slot, playableEmote, hovered);
		}

		drawCenterHex(graphics, metrics);
		drawFooter(graphics, metrics, pageEmotes);
	}

	@Override
	public void mouseMoved(double x, double y) {
		this.lastMouseX = x;
		this.lastMouseY = y;
		updateHoveredSlot(x, y);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		this.lastMouseX = event.x();
		this.lastMouseY = event.y();
		updateHoveredSlot(event.x(), event.y());

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
		this.lastMouseX = event.x();
		this.lastMouseY = event.y();
		updateHoveredSlot(event.x(), event.y());
		return event.button() == LEFT_MOUSE_BUTTON || event.button() == RIGHT_MOUSE_BUTTON;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (getPageCount() <= 1) {
			return false;
		}

		int direction;
		if (scrollY > 0.0D || scrollX > 0.0D) {
			direction = -1;
		} else if (scrollY < 0.0D || scrollX < 0.0D) {
			direction = 1;
		} else {
			return false;
		}

		this.pageIndex = positiveModulo(this.pageIndex + direction, getPageCount());
		updateHoveredSlot(x, y);
		return true;
	}

	public void handleBindingReleased() {
		if (!selectHoveredSlot()) {
			this.onClose();
		}
	}

	private void changePage(int direction, double mouseX, double mouseY) {
		if (getPageCount() <= 1) {
			return;
		}

		this.pageIndex = positiveModulo(this.pageIndex + direction, getPageCount());
		updateHoveredSlot(mouseX, mouseY);
	}

	private void drawSlot(GuiGraphicsExtractor graphics, SlotGeometry slot, PlayableEmote playableEmote, boolean hovered) {
		int fillColor = playableEmote == null
			? SLOT_EMPTY_FILL_COLOR
			: hovered
				? SLOT_HIGHLIGHT_FILL_COLOR
				: SLOT_FILL_COLOR;
		drawHex(graphics, slot.xPoints(), slot.yPoints(), fillColor, SLOT_BORDER_COLOR);

		if (playableEmote == null) {
			return;
		}

		List<FormattedCharSequence> lines = this.font.split(Component.literal(playableEmote.displayName()), slot.textWidth());
		int visibleLineCount = Math.min(2, lines.size());
		int lineStartY = slot.centerY() - (visibleLineCount * this.font.lineHeight) / 2;
		for (int lineIndex = 0; lineIndex < visibleLineCount; lineIndex++) {
			graphics.centeredText(
				this.font,
				lines.get(lineIndex),
				slot.centerX(),
				lineStartY + lineIndex * this.font.lineHeight,
				TITLE_COLOR
			);
		}
	}

	private void drawCenterHex(GuiGraphicsExtractor graphics, WheelMetrics metrics) {
		int[] xPoints = createHexagonXPoints(metrics.centerX(), metrics.centerRadius());
		int[] yPoints = createHexagonYPoints(metrics.centerY(), metrics.centerRadius());
		drawHex(graphics, xPoints, yPoints, CENTER_FILL_COLOR, CENTER_BORDER_COLOR);

		if (this.emotes.isEmpty()) {
			graphics.centeredText(this.font, Component.translatable("screen.emote.wheel.center.no_usable"), metrics.centerX(), metrics.centerY() - 10, TITLE_COLOR);
			graphics.centeredText(this.font, Component.translatable("screen.emote.wheel.center.emotes"), metrics.centerX(), metrics.centerY() + 2, TITLE_COLOR);
			return;
		}

		graphics.centeredText(this.font, (this.pageIndex + 1) + "/" + getPageCount(), metrics.centerX(), metrics.centerY() - 10, TITLE_COLOR);
		graphics.centeredText(this.font, Component.translatable("screen.emote.wheel.center.release"), metrics.centerX(), metrics.centerY() + 2, BODY_COLOR);
		graphics.centeredText(this.font, Component.translatable("screen.emote.wheel.center.to_play"), metrics.centerX(), metrics.centerY() + 12, BODY_COLOR);
	}

	private void drawFooter(GuiGraphicsExtractor graphics, WheelMetrics metrics, List<PlayableEmote> pageEmotes) {
		int footerTop = Math.min(this.height - 70, metrics.centerY() + metrics.ringRadius() + metrics.slotRadius() + 14);
		PlayableEmote hoveredEmote = this.hoveredSlotIndex >= 0 && this.hoveredSlotIndex < pageEmotes.size()
			? pageEmotes.get(this.hoveredSlotIndex)
			: null;

		if (hoveredEmote != null) {
			graphics.centeredText(this.font, Component.literal(hoveredEmote.displayName()), metrics.centerX(), footerTop, TITLE_COLOR);
			graphics.textWithWordWrap(
				this.font,
				Component.literal(hoveredEmote.description()),
				metrics.centerX() - metrics.descriptionWidth() / 2,
				footerTop + 14,
				metrics.descriptionWidth(),
				BODY_COLOR,
				true
			);
			return;
		}

		if (this.emotes.isEmpty()) {
			graphics.centeredText(this.font, Component.translatable("screen.emote.wheel.footer.no_usable"), metrics.centerX(), footerTop + 8, BODY_COLOR);
			return;
		}

		graphics.centeredText(
			this.font,
			Component.translatable("screen.emote.wheel.footer.release_to_play", this.bindingLabel),
			metrics.centerX(),
			footerTop,
			BODY_COLOR
		);
		graphics.centeredText(this.font, Component.translatable("screen.emote.wheel.footer.close"), metrics.centerX(), footerTop + 14, MUTED_COLOR);
		if (getPageCount() > 1) {
			graphics.centeredText(this.font, Component.translatable("screen.emote.wheel.footer.page_click"), metrics.centerX(), footerTop + 28, MUTED_COLOR);
			return;
		}
	}

	private boolean selectHoveredSlot() {
		PlayableEmote playableEmote = getPlayableEmoteAt(this.hoveredSlotIndex);
		if (playableEmote == null) {
			return false;
		}

		this.onClose();
		this.controller.playEmote(playableEmote);
		return true;
	}

	private PlayableEmote getPlayableEmoteAt(int slotIndex) {
		if (slotIndex < 0) {
			return null;
		}

		int emoteIndex = this.pageIndex * SLOT_COUNT + slotIndex;
		return emoteIndex >= 0 && emoteIndex < this.emotes.size()
			? this.emotes.get(emoteIndex)
			: null;
	}

	private List<PlayableEmote> getCurrentPageEmotes() {
		int startIndex = Math.min(this.pageIndex * SLOT_COUNT, this.emotes.size());
		int endIndex = Math.min(startIndex + SLOT_COUNT, this.emotes.size());
		return this.emotes.subList(startIndex, endIndex);
	}

	private int getPageCount() {
		return Math.max(1, (this.emotes.size() + SLOT_COUNT - 1) / SLOT_COUNT);
	}

	private int clampPageIndex(int pageIndex) {
		return Math.max(0, Math.min(pageIndex, getPageCount() - 1));
	}

	private WheelMetrics createMetrics() {
		int centerX = this.width / 2;
		int centerY = this.height / 2 - 18;
		int slotRadius = Mth.clamp(Math.min(this.width, this.height) / 12, 28, 44);
		int ringRadiusLimitX = Math.max(slotRadius * 2, this.width / 2 - slotRadius - 18);
		int ringRadiusLimitY = Math.max(slotRadius * 2, Math.min(centerY - slotRadius - 18, this.height - centerY - slotRadius - 90));
		int ringRadius = Math.max(slotRadius * 2, Math.min(slotRadius * 3 + 20, Math.min(ringRadiusLimitX, ringRadiusLimitY)));
		int centerRadius = Math.max(20, slotRadius - 12);
		int descriptionWidth = Math.min(280, this.width - 48);
		return new WheelMetrics(centerX, centerY, slotRadius, ringRadius, centerRadius, Math.max(52, slotRadius + 24), descriptionWidth);
	}

	private SlotGeometry createSlotGeometry(int slotIndex, WheelMetrics metrics) {
		double angle = -Math.PI / 2.0D + slotIndex * (Math.PI / 3.0D);
		int centerX = metrics.centerX() + (int) Math.round(Math.cos(angle) * metrics.ringRadius());
		int centerY = metrics.centerY() + (int) Math.round(Math.sin(angle) * metrics.ringRadius());
		return new SlotGeometry(
			centerX,
			centerY,
			createHexagonXPoints(centerX, metrics.slotRadius()),
			createHexagonYPoints(centerY, metrics.slotRadius()),
			metrics.textWidth()
		);
	}

	private void updateHoveredSlot(double mouseX, double mouseY) {
		WheelMetrics metrics = createMetrics();
		this.hoveredSlotIndex = -1;

		for (int slotIndex = 0; slotIndex < SLOT_COUNT; slotIndex++) {
			if (getPlayableEmoteAt(slotIndex) == null) {
				continue;
			}

			SlotGeometry slot = createSlotGeometry(slotIndex, metrics);
			if (containsPoint(slot.xPoints(), slot.yPoints(), mouseX, mouseY)) {
				this.hoveredSlotIndex = slotIndex;
				return;
			}
		}
	}

	private int[] createHexagonXPoints(int centerX, int radius) {
		int[] points = new int[6];
		for (int index = 0; index < points.length; index++) {
			double angle = Math.PI * 2.0D * index / points.length;
			points[index] = centerX + (int) Math.round(Math.cos(angle) * radius);
		}
		return points;
	}

	private int[] createHexagonYPoints(int centerY, int radius) {
		int[] points = new int[6];
		for (int index = 0; index < points.length; index++) {
			double angle = Math.PI * 2.0D * index / points.length;
			points[index] = centerY + (int) Math.round(Math.sin(angle) * radius);
		}
		return points;
	}

	private void drawHex(GuiGraphicsExtractor graphics, int[] xPoints, int[] yPoints, int fillColor, int borderColor) {
		fillPolygon(graphics, xPoints, yPoints, borderColor);
		int centerX = average(xPoints);
		int centerY = average(yPoints);
		int innerRadius = Math.max(8, estimateRadius(xPoints, centerX) - 3);
		fillPolygon(graphics, createHexagonXPoints(centerX, innerRadius), createHexagonYPoints(centerY, innerRadius), fillColor);
	}

	private void fillPolygon(GuiGraphicsExtractor graphics, int[] xPoints, int[] yPoints, int color) {
		int minY = Arrays.stream(yPoints).min().orElse(0);
		int maxY = Arrays.stream(yPoints).max().orElse(-1);
		double[] intersections = new double[xPoints.length];

		for (int y = minY; y <= maxY; y++) {
			int intersectionCount = 0;
			double scanY = y + 0.5D;

			for (int currentIndex = 0, previousIndex = xPoints.length - 1; currentIndex < xPoints.length; previousIndex = currentIndex++) {
				int currentY = yPoints[currentIndex];
				int previousY = yPoints[previousIndex];
				if (currentY == previousY) {
					continue;
				}

				double lowerY = Math.min(currentY, previousY);
				double upperY = Math.max(currentY, previousY);
				if (scanY < lowerY || scanY >= upperY) {
					continue;
				}

				int currentX = xPoints[currentIndex];
				int previousX = xPoints[previousIndex];
				intersections[intersectionCount++] = currentX + (scanY - currentY) * (previousX - currentX) / (previousY - currentY);
			}

			Arrays.sort(intersections, 0, intersectionCount);
			for (int index = 0; index + 1 < intersectionCount; index += 2) {
				int startX = Mth.floor(intersections[index]);
				int endX = Mth.ceil(intersections[index + 1]);
				graphics.fill(startX, y, endX, y + 1, color);
			}
		}
	}

	private boolean containsPoint(int[] xPoints, int[] yPoints, double mouseX, double mouseY) {
		boolean inside = false;
		for (int currentIndex = 0, previousIndex = xPoints.length - 1; currentIndex < xPoints.length; previousIndex = currentIndex++) {
			boolean intersects = (yPoints[currentIndex] > mouseY) != (yPoints[previousIndex] > mouseY)
				&& mouseX < (double) (xPoints[previousIndex] - xPoints[currentIndex]) * (mouseY - yPoints[currentIndex])
					/ (double) (yPoints[previousIndex] - yPoints[currentIndex]) + xPoints[currentIndex];
			if (intersects) {
				inside = !inside;
			}
		}
		return inside;
	}

	private int average(int[] values) {
		int sum = 0;
		for (int value : values) {
			sum += value;
		}
		return sum / values.length;
	}

	private int estimateRadius(int[] values, int center) {
		int total = 0;
		for (int value : values) {
			total += Math.abs(value - center);
		}
		return Math.max(1, total / values.length);
	}

	private int positiveModulo(int value, int divisor) {
		int result = value % divisor;
		return result < 0 ? result + divisor : result;
	}

	private record WheelMetrics(
		int centerX,
		int centerY,
		int slotRadius,
		int ringRadius,
		int centerRadius,
		int textWidth,
		int descriptionWidth
	) {
	}

	private record SlotGeometry(
		int centerX,
		int centerY,
		int[] xPoints,
		int[] yPoints,
		int textWidth
	) {
	}
}
