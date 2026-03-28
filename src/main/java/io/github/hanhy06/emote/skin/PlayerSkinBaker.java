package io.github.hanhy06.emote.skin;

import javax.imageio.ImageIO;
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
		if (skinPart == PlayerSkinPart.HEAD) {
			return writePng(normalizedImage);
		}

		BufferedImage outputImage = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		FaceMap baseFaces = getBaseFaces(skinPart, slimModel);
		FaceMap overlayFaces = getOverlayFaces(skinPart, slimModel);

		drawTopFace(outputImage, normalizedImage, baseFaces, skinSegment, BASE_TOP);
		drawBottomFace(outputImage, normalizedImage, baseFaces, skinSegment, BASE_BOTTOM);
		drawFace(outputImage, normalizedImage, createSegment(baseFaces.right(), skinSegment), BASE_RIGHT);
		drawFace(outputImage, normalizedImage, createSegment(baseFaces.front(), skinSegment), BASE_FRONT);
		drawFace(outputImage, normalizedImage, createSegment(baseFaces.left(), skinSegment), BASE_LEFT);
		drawFace(outputImage, normalizedImage, createSegment(baseFaces.back(), skinSegment), BASE_BACK);

		drawTopFace(outputImage, normalizedImage, overlayFaces, skinSegment, OVERLAY_TOP);
		drawBottomFace(outputImage, normalizedImage, overlayFaces, skinSegment, OVERLAY_BOTTOM);
		drawFace(outputImage, normalizedImage, createSegment(overlayFaces.right(), skinSegment), OVERLAY_RIGHT);
		drawFace(outputImage, normalizedImage, createSegment(overlayFaces.front(), skinSegment), OVERLAY_FRONT);
		drawFace(outputImage, normalizedImage, createSegment(overlayFaces.left(), skinSegment), OVERLAY_LEFT);
		drawFace(outputImage, normalizedImage, createSegment(overlayFaces.back(), skinSegment), OVERLAY_BACK);

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

		copyMirroredArea(normalizedImage, 0, 16, 16, 16, 16, 48);
		copyMirroredArea(normalizedImage, 40, 16, 16, 16, 32, 48);
		return normalizedImage;
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
		for (int x = 0; x < targetRect.width(); x++) {
			int sourceX = x * sourceRect.width() / targetRect.width();
			if (sourceRect.flipX()) {
				sourceX = sourceRect.width() - 1 - sourceX;
			}

			sourceX += sourceRect.x();
			for (int y = 0; y < targetRect.height(); y++) {
				int sourceY = sourceRect.y() + y * sourceRect.height() / targetRect.height();
				outputImage.setRGB(targetRect.x() + x, targetRect.y() + y, sourceImage.getRGB(sourceX, sourceY));
			}
		}
	}

	private void drawFace(BufferedImage outputImage, BufferedImage sourceFaceImage, FaceTarget targetRect) {
		for (int x = 0; x < targetRect.width(); x++) {
			int sourceX = x * sourceFaceImage.getWidth() / targetRect.width();
			for (int y = 0; y < targetRect.height(); y++) {
				int sourceY = y * sourceFaceImage.getHeight() / targetRect.height();
				outputImage.setRGB(targetRect.x() + x, targetRect.y() + y, sourceFaceImage.getRGB(sourceX, sourceY));
			}
		}
	}

	private byte[] writePng(BufferedImage image) throws IOException {
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		ImageIO.write(image, "png", outputStream);
		return outputStream.toByteArray();
	}

	private FaceMap getBaseFaces(PlayerSkinPart skinPart, boolean slimModel) {
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

		return new FaceRect(faceRect.x(), faceRect.y() + startOffset, faceRect.width(), endOffset - startOffset, faceRect.flipX());
	}

	private FaceMap orientFaces(PlayerSkinPart skinPart, FaceMap faceMap) {
		return switch (skinPart) {
			case HEAD, BODY -> faceMap;
			case RIGHT_ARM -> new FaceMap(
				flipX(faceMap.top()),
				flipX(faceMap.bottom()),
				flipX(faceMap.right()),
				flipX(faceMap.front()),
				flipX(faceMap.left()),
				flipX(faceMap.back())
			);
			case LEFT_ARM -> new FaceMap(
				flipX(faceMap.bottom()),
				flipX(faceMap.top()),
				flipX(faceMap.back()),
				flipX(faceMap.left()),
				flipX(faceMap.front()),
				flipX(faceMap.right())
			);
			case RIGHT_LEG -> new FaceMap(
				flipX(faceMap.top()),
				flipX(faceMap.bottom()),
				flipX(faceMap.right()),
				flipX(faceMap.front()),
				flipX(faceMap.left()),
				flipX(faceMap.back())
			);
			case LEFT_LEG -> new FaceMap(
				flipX(faceMap.bottom()),
				flipX(faceMap.top()),
				flipX(faceMap.back()),
				flipX(faceMap.left()),
				flipX(faceMap.front()),
				flipX(faceMap.right())
			);
		};
	}

	private FaceRect flipX(FaceRect faceRect) {
		if (faceRect.flipX()) {
			return new FaceRect(faceRect.x(), faceRect.y(), faceRect.width(), faceRect.height());
		}

		return new FaceRect(faceRect.x(), faceRect.y(), faceRect.width(), faceRect.height(), true);
	}

	private void drawTopFace(
		BufferedImage outputImage,
		BufferedImage sourceImage,
		FaceMap fullFaces,
		PlayerSkinSegment skinSegment,
		FaceTarget targetRect
	) {
		drawFace(outputImage, sourceImage, fullFaces.top(), targetRect);
	}

	private void drawBottomFace(
		BufferedImage outputImage,
		BufferedImage sourceImage,
		FaceMap fullFaces,
		PlayerSkinSegment skinSegment,
		FaceTarget targetRect
	) {
		drawFace(outputImage, sourceImage, fullFaces.bottom(), targetRect);
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
			new FaceRect(44, 16, 3, 4),
			new FaceRect(47, 16, 3, 4),
			new FaceRect(40, 20, 4, 12),
			new FaceRect(44, 20, 3, 12),
			new FaceRect(47, 20, 4, 12),
			new FaceRect(51, 20, 3, 12)
		);
	}

	private FaceMap createSlimRightArmOverlayFaces() {
		return new FaceMap(
			new FaceRect(44, 32, 3, 4),
			new FaceRect(47, 32, 3, 4),
			new FaceRect(40, 36, 4, 12),
			new FaceRect(44, 36, 3, 12),
			new FaceRect(47, 36, 4, 12),
			new FaceRect(51, 36, 3, 12)
		);
	}

	private FaceMap createSlimLeftArmFaces() {
		return new FaceMap(
			new FaceRect(36, 48, 3, 4),
			new FaceRect(39, 48, 3, 4),
			new FaceRect(32, 52, 4, 12),
			new FaceRect(36, 52, 3, 12),
			new FaceRect(39, 52, 4, 12),
			new FaceRect(43, 52, 3, 12)
		);
	}

	private FaceMap createSlimLeftArmOverlayFaces() {
		return new FaceMap(
			new FaceRect(52, 48, 3, 4),
			new FaceRect(55, 48, 3, 4),
			new FaceRect(48, 52, 4, 12),
			new FaceRect(52, 52, 3, 12),
			new FaceRect(55, 52, 4, 12),
			new FaceRect(59, 52, 3, 12)
		);
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

	private record FaceRect(int x, int y, int width, int height, boolean flipX) {
		private FaceRect(int x, int y, int width, int height) {
			this(x, y, width, height, false);
		}
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
