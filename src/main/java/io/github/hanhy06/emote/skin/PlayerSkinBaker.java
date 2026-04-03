package io.github.hanhy06.emote.skin;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class PlayerSkinBaker {
	private static final FaceTarget BASE_TOP = new FaceTarget(8, 0, 8, 8);
	private static final FaceTarget BASE_BOTTOM = new FaceTarget(16, 0, 8, 8);
	private static final FaceTarget BASE_RIGHT = new FaceTarget(0, 8, 8, 8);
	private static final FaceTarget BASE_FRONT = new FaceTarget(8, 8, 8, 8);
	private static final FaceTarget BASE_LEFT = new FaceTarget(16, 8, 8, 8);
	private static final FaceTarget BASE_BACK = new FaceTarget(24, 8, 8, 8);

	private static final FaceTarget OVERLAY_TOP = new FaceTarget(40, 0, 8, 8);
	private static final FaceTarget OVERLAY_BOTTOM = new FaceTarget(48, 0, 8, 8);
	private static final FaceTarget OVERLAY_RIGHT = new FaceTarget(32, 8, 8, 8);
	private static final FaceTarget OVERLAY_FRONT = new FaceTarget(40, 8, 8, 8);
	private static final FaceTarget OVERLAY_LEFT = new FaceTarget(48, 8, 8, 8);
	private static final FaceTarget OVERLAY_BACK = new FaceTarget(56, 8, 8, 8);

	public byte[] bake(BufferedImage sourceImage, PlayerSkinPart skinPart, PlayerSkinSegment skinSegment, boolean slimModel) throws IOException {
		BufferedImage normalizedImage = normalizeSkinImage(sourceImage);

		// [수정됨] 머리(HEAD)일 때 스킨 전체를 반환하던 버그(Shortcut) 제거
		// 이제 머리도 아래 로직을 타면서 머리 좌표만 깨끗하게 잘려나갑니다.

		boolean useWideSlimArmAtlas = usesWideSlimArmAtlas(skinPart, slimModel);
		BufferedImage bakingImage = useWideSlimArmAtlas ? expandSlimArmToWideAtlas(normalizedImage, skinPart) : normalizedImage;
		BufferedImage outputImage = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		FaceMap baseFaces = getBaseFaces(skinPart, slimModel);
		FaceMap overlayFaces = getOverlayFaces(skinPart, slimModel);

		drawTopFace(outputImage, bakingImage, baseFaces, BASE_TOP);
		drawBottomFace(outputImage, bakingImage, baseFaces, BASE_BOTTOM);
		drawFace(outputImage, bakingImage, createSegment(baseFaces.right(), skinSegment), BASE_RIGHT);
		drawFace(outputImage, bakingImage, createSegment(baseFaces.front(), skinSegment), BASE_FRONT);
		drawFace(outputImage, bakingImage, createSegment(baseFaces.left(), skinSegment), BASE_LEFT);
		drawFace(outputImage, bakingImage, createSegment(baseFaces.back(), skinSegment), BASE_BACK);

		drawTopFace(outputImage, bakingImage, overlayFaces, OVERLAY_TOP);
		drawBottomFace(outputImage, bakingImage, overlayFaces, OVERLAY_BOTTOM);
		drawFace(outputImage, bakingImage, createSegment(overlayFaces.right(), skinSegment), OVERLAY_RIGHT);
		drawFace(outputImage, bakingImage, createSegment(overlayFaces.front(), skinSegment), OVERLAY_FRONT);
		drawFace(outputImage, bakingImage, createSegment(overlayFaces.left(), skinSegment), OVERLAY_LEFT);
		drawFace(outputImage, bakingImage, createSegment(overlayFaces.back(), skinSegment), OVERLAY_BACK);

		return writePng(outputImage);
	}

	private BufferedImage normalizeSkinImage(BufferedImage sourceImage) {
		if (sourceImage.getWidth() != 64 || (sourceImage.getHeight() != 32 && sourceImage.getHeight() != 64)) {
			throw new IllegalArgumentException("skin image must be 64x32 or 64x64");
		}

		if (sourceImage.getHeight() == 64) {
			return sourceImage;
		}

		BufferedImage normalizedImage = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		for (int x = 0; x < 64; x++) {
			for (int y = 0; y < 32; y++) {
				normalizedImage.setRGB(x, y, sourceImage.getRGB(x, y));
			}
		}

		// [수정됨] 통째로 미러링하는 대신 개별 면(Face) 단위로 잘라서 조립하도록 교체
		copyLegacyLimb(normalizedImage, 0, 16, 16, 48);  // Right Leg -> Left Leg
		copyLegacyLimb(normalizedImage, 40, 16, 32, 48); // Right Arm -> Left Arm
		return normalizedImage;
	}

	// [추가됨] 64x32 구버전 스킨을 마인크래프트 공식 원리대로 정확하게 좌우 반전시켜 매핑하는 함수
	private void copyLegacyLimb(BufferedImage image, int srcX, int srcY, int dstX, int dstY) {
		copyMirroredArea(image, srcX + 4, srcY, 4, 4, dstX + 4, dstY);            // Top
		copyMirroredArea(image, srcX + 8, srcY, 4, 4, dstX + 8, dstY);            // Bottom
		copyMirroredArea(image, srcX, srcY + 4, 4, 12, dstX + 8, dstY + 4);       // Right (Outer) -> Left (Outer)
		copyMirroredArea(image, srcX + 4, srcY + 4, 4, 12, dstX + 4, dstY + 4);   // Front -> Front
		copyMirroredArea(image, srcX + 8, srcY + 4, 4, 12, dstX, dstY + 4);       // Left (Inner) -> Right (Inner)
		copyMirroredArea(image, srcX + 12, srcY + 4, 4, 12, dstX + 12, dstY + 4); // Back -> Back
	}

	private void copyMirroredArea(BufferedImage image, int sourceX, int sourceY, int width, int height, int targetX, int targetY) {
		for (int x = 0; x < width; x++) {
			for (int y = 0; y < height; y++) {
				int color = image.getRGB(sourceX + (width - 1 - x), sourceY + y);
				image.setRGB(targetX + x, targetY + y, color);
			}
		}
	}

	private void drawFace(BufferedImage outputImage, BufferedImage sourceImage, FaceRect sourceRect, FaceTarget targetRect) {
		int localWidth = sourceRect.virtualWidth();
		int localHeight = sourceRect.height();
		for (int x = 0; x < targetRect.width(); x++) {
			for (int y = 0; y < targetRect.height(); y++) {
				int sourceX = x * localWidth / targetRect.width();
				int sourceY = y * localHeight / targetRect.height();
				int[] rotated = rotateSample(sourceX, sourceY, localWidth, localHeight, sourceRect.rotateQuarterTurns());
				sourceX = rotated[0];
				sourceY = rotated[1];

				if (sourceRect.flipX()) {
					sourceX = localWidth - 1 - sourceX;
				}
				if (sourceRect.flipY()) {
					sourceY = localHeight - 1 - sourceY;
				}

				sourceX = mapVirtualX(sourceX, sourceRect.width(), localWidth, sourceRect.padMode());
				sourceX += sourceRect.x();
				sourceY += sourceRect.y();
				outputImage.setRGB(targetRect.x() + x, targetRect.y() + y, sourceImage.getRGB(sourceX, sourceY));
			}
		}
	}

	private int[] rotateSample(int sourceX, int sourceY, int width, int height, int rotateQuarterTurns) {
		return switch (Math.floorMod(rotateQuarterTurns, 4)) {
			case 1 -> new int[]{sourceY, width - 1 - sourceX};
			case 2 -> new int[]{width - 1 - sourceX, height - 1 - sourceY};
			case 3 -> new int[]{height - 1 - sourceY, sourceX};
			default -> new int[]{sourceX, sourceY};
		};
	}

	private int mapVirtualX(int virtualX, int sourceWidth, int virtualWidth, PadMode padMode) {
		if (sourceWidth <= 1 || virtualWidth <= 1 || sourceWidth == virtualWidth) {
			return Math.max(0, Math.min(sourceWidth - 1, virtualX));
		}

		int paddingWidth = virtualWidth - sourceWidth;
		int sourceX = switch (padMode) {
			case LEFT -> Math.max(0, virtualX - paddingWidth);
			case RIGHT -> Math.min(sourceWidth - 1, virtualX);
			case NONE -> (int) Math.round(virtualX * (sourceWidth - 1) / (double) (virtualWidth - 1));
		};
		return Math.max(0, Math.min(sourceWidth - 1, sourceX));
	}

	private byte[] writePng(BufferedImage image) throws IOException {
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		ImageIO.write(image, "png", outputStream);
		return outputStream.toByteArray();
	}

	private FaceMap getBaseFaces(PlayerSkinPart skinPart, boolean slimModel) {
		if (slimModel && skinPart == PlayerSkinPart.RIGHT_ARM) {
			return createWideRightArmFaces();
		}
		if (slimModel && skinPart == PlayerSkinPart.LEFT_ARM) {
			return createWideLeftArmFaces();
		}
		if (slimModel && skinPart == PlayerSkinPart.LEFT_LEG) {
			return orientSlimLeftLegFaces(createLeftLegFaces());
		}

		return orientFaces(skinPart, switch (skinPart) {
			case BODY -> new FaceMap(
					new FaceRect(20, 16, 8, 4),
					new FaceRect(28, 16, 8, 4),
					new FaceRect(16, 20, 4, 12),
					new FaceRect(20, 20, 8, 12),
					new FaceRect(28, 20, 4, 12),
					new FaceRect(32, 20, 8, 12)
			);
			case RIGHT_ARM -> slimModel ? createSlimRightArmFaces() : createWideRightArmFaces();
			case LEFT_ARM -> slimModel ? createSlimLeftArmFaces() : createWideLeftArmFaces();
			case RIGHT_LEG -> createRightLegFaces();
			case LEFT_LEG -> createLeftLegFaces();
			case HEAD -> createHeadFaces();
		});
	}

	private FaceMap getOverlayFaces(PlayerSkinPart skinPart, boolean slimModel) {
		if (slimModel && skinPart == PlayerSkinPart.RIGHT_ARM) {
			return createWideRightArmOverlayFaces();
		}
		if (slimModel && skinPart == PlayerSkinPart.LEFT_ARM) {
			return createWideLeftArmOverlayFaces();
		}
		if (slimModel && skinPart == PlayerSkinPart.LEFT_LEG) {
			return orientSlimLeftLegFaces(createLeftLegOverlayFaces());
		}

		return orientFaces(skinPart, switch (skinPart) {
			case BODY -> new FaceMap(
					new FaceRect(20, 32, 8, 4),
					new FaceRect(28, 32, 8, 4),
					new FaceRect(16, 36, 4, 12),
					new FaceRect(20, 36, 8, 12),
					new FaceRect(28, 36, 4, 12),
					new FaceRect(32, 36, 8, 12)
			);
			case RIGHT_ARM -> slimModel ? createSlimRightArmOverlayFaces() : createWideRightArmOverlayFaces();
			case LEFT_ARM -> slimModel ? createSlimLeftArmOverlayFaces() : createWideLeftArmOverlayFaces();
			case RIGHT_LEG -> createRightLegOverlayFaces();
			case LEFT_LEG -> createLeftLegOverlayFaces();
			case HEAD -> createHeadOverlayFaces();
		});
	}

	private FaceRect createSegment(FaceRect faceRect, PlayerSkinSegment skinSegment) {
		int startOffset = faceRect.height() * skinSegment.startY() / PlayerSkinSegment.SIDE_FACE_HEIGHT;
		int endOffset = faceRect.height() * skinSegment.endY() / PlayerSkinSegment.SIDE_FACE_HEIGHT;
		if (endOffset <= startOffset) {
			endOffset = Math.min(faceRect.height(), startOffset + 1);
		}

		return new FaceRect(
				faceRect.x(),
				faceRect.y() + startOffset,
				faceRect.width(),
				endOffset - startOffset,
				faceRect.flipX(),
				faceRect.flipY(),
				faceRect.rotateQuarterTurns(),
				faceRect.virtualWidth(),
				faceRect.padMode()
		);
	}

	private FaceMap orientSlimLeftLegFaces(FaceMap faceMap) {
		return rotateQuarterTurnCcw(orientFaces(PlayerSkinPart.LEFT_LEG, faceMap));
	}

	// [수정됨] 스킨이 올바르게 생성되게끔 비정상적인 면 뒤섞기 로직(Hack) 모두 제거
	private FaceMap orientFaces(PlayerSkinPart skinPart, FaceMap faceMap) {
		return faceMap;
	}

	private FaceRect flipX(FaceRect faceRect) {
		if (faceRect.flipX()) {
			return withFlipX(faceRect, false);
		}

		return withFlipX(faceRect, true);
	}

	private FaceRect flipY(FaceRect faceRect) {
		if (faceRect.flipY()) {
			return withFlipY(faceRect, false);
		}

		return withFlipY(faceRect, true);
	}

	private FaceRect withFlipX(FaceRect faceRect, boolean flipX) {
		return new FaceRect(
				faceRect.x(),
				faceRect.y(),
				faceRect.width(),
				faceRect.height(),
				flipX,
				faceRect.flipY(),
				faceRect.rotateQuarterTurns(),
				faceRect.virtualWidth(),
				faceRect.padMode()
		);
	}

	private FaceRect withFlipY(FaceRect faceRect, boolean flipY) {
		return new FaceRect(
				faceRect.x(),
				faceRect.y(),
				faceRect.width(),
				faceRect.height(),
				faceRect.flipX(),
				flipY,
				faceRect.rotateQuarterTurns(),
				faceRect.virtualWidth(),
				faceRect.padMode()
		);
	}

	private FaceRect rotateQuarterTurns(FaceRect faceRect, int rotateQuarterTurns) {
		return new FaceRect(
				faceRect.x(),
				faceRect.y(),
				faceRect.width(),
				faceRect.height(),
				faceRect.flipX(),
				faceRect.flipY(),
				faceRect.rotateQuarterTurns() + rotateQuarterTurns,
				faceRect.virtualWidth(),
				faceRect.padMode()
		);
	}

	private FaceMap rotateHalfTurn(FaceMap faceMap) {
		return new FaceMap(
				flipY(flipX(faceMap.top())),
				flipY(flipX(faceMap.bottom())),
				flipX(faceMap.left()),
				flipX(faceMap.back()),
				flipX(faceMap.right()),
				flipX(faceMap.front())
		);
	}

	private FaceMap rotateQuarterTurnCw(FaceMap faceMap) {
		return new FaceMap(
				rotateQuarterTurns(faceMap.top(), 1),
				rotateQuarterTurns(faceMap.bottom(), 1),
				faceMap.back(),
				faceMap.right(),
				faceMap.front(),
				faceMap.left()
		);
	}

	private FaceMap rotateQuarterTurnCcw(FaceMap faceMap) {
		return rotateHalfTurn(rotateQuarterTurnCw(faceMap));
	}

	private void drawTopFace(
			BufferedImage outputImage,
			BufferedImage sourceImage,
			FaceMap fullFaces,
			FaceTarget targetRect
	) {
		drawFace(outputImage, sourceImage, fullFaces.top(), targetRect);
	}

	private void drawBottomFace(
			BufferedImage outputImage,
			BufferedImage sourceImage,
			FaceMap fullFaces,
			FaceTarget targetRect
	) {
		drawFace(outputImage, sourceImage, fullFaces.bottom(), targetRect);
	}

	private boolean usesWideSlimArmAtlas(PlayerSkinPart skinPart, boolean slimModel) {
		return slimModel && (skinPart == PlayerSkinPart.RIGHT_ARM || skinPart == PlayerSkinPart.LEFT_ARM);
	}

	private BufferedImage expandSlimArmToWideAtlas(BufferedImage sourceImage, PlayerSkinPart skinPart) {
		BufferedImage expandedImage = copyImage(sourceImage);
		switch (skinPart) {
			case RIGHT_ARM -> {
				copyFaceMap(expandedImage, sourceImage, createSlimLeftArmFaces(), createWideRightArmFaces());
				copyFaceMap(expandedImage, sourceImage, createSlimLeftArmOverlayFaces(), createWideRightArmOverlayFaces());
			}
			case LEFT_ARM -> {
				copyFaceMap(expandedImage, sourceImage, createSlimRightArmFaces(), createWideLeftArmFaces());
				copyFaceMap(expandedImage, sourceImage, createSlimRightArmOverlayFaces(), createWideLeftArmOverlayFaces());
			}
			default -> {
			}
		}
		return expandedImage;
	}

	private BufferedImage copyImage(BufferedImage sourceImage) {
		BufferedImage copiedImage = new BufferedImage(sourceImage.getWidth(), sourceImage.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = copiedImage.createGraphics();
		graphics.drawImage(sourceImage, 0, 0, null);
		graphics.dispose();
		return copiedImage;
	}

	private void copyFaceMap(BufferedImage targetImage, BufferedImage sourceImage, FaceMap sourceFaces, FaceMap targetFaces) {
		copyFace(targetImage, sourceImage, sourceFaces.top(), targetFaces.top());
		copyFace(targetImage, sourceImage, sourceFaces.bottom(), targetFaces.bottom());
		copyFace(targetImage, sourceImage, sourceFaces.right(), targetFaces.right());
		copyFace(targetImage, sourceImage, sourceFaces.front(), targetFaces.front());
		copyFace(targetImage, sourceImage, sourceFaces.left(), targetFaces.left());
		copyFace(targetImage, sourceImage, sourceFaces.back(), targetFaces.back());
	}

	private void copyFace(BufferedImage targetImage, BufferedImage sourceImage, FaceRect sourceRect, FaceRect targetRect) {
		drawFace(targetImage, sourceImage, sourceRect, new FaceTarget(targetRect.x(), targetRect.y(), targetRect.width(), targetRect.height()));
	}

	private FaceMap createHeadFaces() {
		return new FaceMap(
				new FaceRect(8, 0, 8, 8),
				new FaceRect(16, 0, 8, 8),
				new FaceRect(0, 8, 8, 8),
				new FaceRect(8, 8, 8, 8),
				new FaceRect(16, 8, 8, 8),
				new FaceRect(24, 8, 8, 8)
		);
	}

	private FaceMap createHeadOverlayFaces() {
		return new FaceMap(
				new FaceRect(40, 0, 8, 8),
				new FaceRect(48, 0, 8, 8),
				new FaceRect(32, 8, 8, 8),
				new FaceRect(40, 8, 8, 8),
				new FaceRect(48, 8, 8, 8),
				new FaceRect(56, 8, 8, 8)
		);
	}

	private FaceMap createWideRightArmFaces() {
		return new FaceMap(
				new FaceRect(44, 16, 4, 4),
				new FaceRect(48, 16, 4, 4),
				new FaceRect(40, 20, 4, 12),
				new FaceRect(44, 20, 4, 12),
				new FaceRect(48, 20, 4, 12),
				new FaceRect(52, 20, 4, 12)
		);
	}

	private FaceMap createWideRightArmOverlayFaces() {
		return new FaceMap(
				new FaceRect(44, 32, 4, 4),
				new FaceRect(48, 32, 4, 4),
				new FaceRect(40, 36, 4, 12),
				new FaceRect(44, 36, 4, 12),
				new FaceRect(48, 36, 4, 12),
				new FaceRect(52, 36, 4, 12)
		);
	}

	private FaceMap createWideLeftArmFaces() {
		return new FaceMap(
				new FaceRect(36, 48, 4, 4),
				new FaceRect(40, 48, 4, 4),
				new FaceRect(32, 52, 4, 12),
				new FaceRect(36, 52, 4, 12),
				new FaceRect(40, 52, 4, 12),
				new FaceRect(44, 52, 4, 12)
		);
	}

	private FaceMap createWideLeftArmOverlayFaces() {
		return new FaceMap(
				new FaceRect(52, 48, 4, 4),
				new FaceRect(56, 48, 4, 4),
				new FaceRect(48, 52, 4, 12),
				new FaceRect(52, 52, 4, 12),
				new FaceRect(56, 52, 4, 12),
				new FaceRect(60, 52, 4, 12)
		);
	}

	private FaceMap createSlimRightArmFaces() {
		return new FaceMap(
				createSlimRect(44, 16, 3, 4, PadMode.LEFT),
				createSlimRect(47, 16, 3, 4, PadMode.LEFT),
				new FaceRect(40, 20, 4, 12),
				createSlimRect(44, 20, 3, 12, PadMode.LEFT),
				new FaceRect(47, 20, 4, 12),
				createSlimRect(51, 20, 3, 12, PadMode.RIGHT)
		);
	}

	private FaceMap createSlimRightArmOverlayFaces() {
		return new FaceMap(
				createSlimRect(44, 32, 3, 4, PadMode.LEFT),
				createSlimRect(47, 32, 3, 4, PadMode.LEFT),
				new FaceRect(40, 36, 4, 12),
				createSlimRect(44, 36, 3, 12, PadMode.LEFT),
				new FaceRect(47, 36, 4, 12),
				createSlimRect(51, 36, 3, 12, PadMode.RIGHT)
		);
	}

	private FaceMap createSlimLeftArmFaces() {
		return new FaceMap(
				createSlimRect(36, 48, 3, 4, PadMode.RIGHT),
				createSlimRect(39, 48, 3, 4, PadMode.RIGHT),
				new FaceRect(32, 52, 4, 12),
				createSlimRect(36, 52, 3, 12, PadMode.RIGHT),
				new FaceRect(39, 52, 4, 12),
				createSlimRect(43, 52, 3, 12, PadMode.LEFT)
		);
	}

	private FaceMap createSlimLeftArmOverlayFaces() {
		return new FaceMap(
				createSlimRect(52, 48, 3, 4, PadMode.RIGHT),
				createSlimRect(55, 48, 3, 4, PadMode.RIGHT),
				new FaceRect(48, 52, 4, 12),
				createSlimRect(52, 52, 3, 12, PadMode.RIGHT),
				new FaceRect(55, 52, 4, 12),
				createSlimRect(59, 52, 3, 12, PadMode.LEFT)
		);
	}

	private FaceRect createSlimRect(int x, int y, int width, int height, PadMode padMode) {
		return new FaceRect(x, y, width, height, false, false, 0, width + 1, padMode);
	}

	private FaceMap createRightLegFaces() {
		return new FaceMap(
				new FaceRect(4, 16, 4, 4),
				new FaceRect(8, 16, 4, 4),
				new FaceRect(0, 20, 4, 12),
				new FaceRect(4, 20, 4, 12),
				new FaceRect(8, 20, 4, 12),
				new FaceRect(12, 20, 4, 12)
		);
	}

	private FaceMap createRightLegOverlayFaces() {
		return new FaceMap(
				new FaceRect(4, 32, 4, 4),
				new FaceRect(8, 32, 4, 4),
				new FaceRect(0, 36, 4, 12),
				new FaceRect(4, 36, 4, 12),
				new FaceRect(8, 36, 4, 12),
				new FaceRect(12, 36, 4, 12)
		);
	}

	private FaceMap createLeftLegFaces() {
		return new FaceMap(
				new FaceRect(20, 48, 4, 4),
				new FaceRect(24, 48, 4, 4),
				new FaceRect(16, 52, 4, 12),
				new FaceRect(20, 52, 4, 12),
				new FaceRect(24, 52, 4, 12),
				new FaceRect(28, 52, 4, 12)
		);
	}

	private FaceMap createLeftLegOverlayFaces() {
		return new FaceMap(
				new FaceRect(4, 48, 4, 4),
				new FaceRect(8, 48, 4, 4),
				new FaceRect(0, 52, 4, 12),
				new FaceRect(4, 52, 4, 12),
				new FaceRect(8, 52, 4, 12),
				new FaceRect(12, 52, 4, 12)
		);
	}

	private record FaceRect(
			int x,
			int y,
			int width,
			int height,
			boolean flipX,
			boolean flipY,
			int rotateQuarterTurns,
			int virtualWidth,
			PadMode padMode
	) {
		private FaceRect(int x, int y, int width, int height) {
			this(x, y, width, height, false, false, 0, width, PadMode.NONE);
		}
	}

	private enum PadMode {
		NONE,
		LEFT,
		RIGHT
	}

	private record FaceTarget(int x, int y, int width, int height) {
	}

	private record FaceMap(
			FaceRect top,
			FaceRect bottom,
			FaceRect right,
			FaceRect front,
			FaceRect left,
			FaceRect back
	) {
	}
}