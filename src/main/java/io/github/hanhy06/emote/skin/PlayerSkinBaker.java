package io.github.hanhy06.emote.skin;

import io.github.hanhy06.emote.skin.model.PlayerSkinPart;
import io.github.hanhy06.emote.skin.model.PlayerSkinSegment;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

final class PlayerSkinBaker {
    private static final Map<PlayerSkinPart, PartAtlas> PARTS = Map.of(
        PlayerSkinPart.HEAD, new PartAtlas(
            faces(8, 0, 8, 8, 0, 8, 8, 8),
            faces(40, 0, 8, 8, 32, 8, 8, 8),
            null,
            null
        ),
        PlayerSkinPart.BODY, new PartAtlas(
            faces(20, 16, 8, 4, 16, 20, 4, 12),
            faces(20, 32, 8, 4, 16, 36, 4, 12),
            null,
            null
        ),
        PlayerSkinPart.RIGHT_ARM, new PartAtlas(
            faces(44, 16, 4, 4, 40, 20, 4, 12),
            faces(44, 32, 4, 4, 40, 36, 4, 12),
            slimRightArmFaces(16, 20),
            slimRightArmFaces(32, 36)
        ),
        PlayerSkinPart.LEFT_ARM, new PartAtlas(
            faces(36, 48, 4, 4, 32, 52, 4, 12),
            faces(52, 48, 4, 4, 48, 52, 4, 12),
            slimLeftArmFaces(false),
            slimLeftArmFaces(true)
        ),
        PlayerSkinPart.RIGHT_LEG, new PartAtlas(
            faces(4, 16, 4, 4, 0, 20, 4, 12),
            faces(4, 32, 4, 4, 0, 36, 4, 12),
            null,
            null
        ),
        PlayerSkinPart.LEFT_LEG, new PartAtlas(
            faces(20, 48, 4, 4, 16, 52, 4, 12),
            faces(4, 48, 4, 4, 0, 52, 4, 12),
            null,
            null
        )
    );
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

    PreparedSkin prepare(BufferedImage sourceImage, boolean slimModel) {
        boolean effectiveSlimModel = resolveSlimModel(sourceImage, slimModel);
        BufferedImage normalizedImage = normalizeSkinImage(sourceImage);
        return new PreparedSkin(normalizedImage, effectiveSlimModel);
    }

    byte[] bake(
        PreparedSkin preparedSkin,
        PlayerSkinPart skinPart,
        PlayerSkinSegment skinSegment
    ) throws IOException {
        BufferedImage bakingImage = preparedSkin.imageFor(skinPart);
        BufferedImage outputImage = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        FaceMap baseFaces = baseFaces(skinPart);
        FaceMap overlayFaces = overlayFaces(skinPart);

        drawFace(outputImage, bakingImage, baseFaces.top(), BASE_TOP);
        drawFace(outputImage, bakingImage, baseFaces.bottom(), BASE_BOTTOM);
        drawFace(outputImage, bakingImage, createSegment(baseFaces.right(), skinSegment), BASE_RIGHT);
        drawFace(outputImage, bakingImage, createSegment(baseFaces.front(), skinSegment), BASE_FRONT);
        drawFace(outputImage, bakingImage, createSegment(baseFaces.left(), skinSegment), BASE_LEFT);
        drawFace(outputImage, bakingImage, createSegment(baseFaces.back(), skinSegment), BASE_BACK);

        drawFace(outputImage, bakingImage, overlayFaces.top(), OVERLAY_TOP);
        drawFace(outputImage, bakingImage, overlayFaces.bottom(), OVERLAY_BOTTOM);
        drawFace(outputImage, bakingImage, createSegment(overlayFaces.right(), skinSegment), OVERLAY_RIGHT);
        drawFace(outputImage, bakingImage, createSegment(overlayFaces.front(), skinSegment), OVERLAY_FRONT);
        drawFace(outputImage, bakingImage, createSegment(overlayFaces.left(), skinSegment), OVERLAY_LEFT);
        drawFace(outputImage, bakingImage, createSegment(overlayFaces.back(), skinSegment), OVERLAY_BACK);

        return writePng(outputImage);
    }

    boolean resolveSlimModel(BufferedImage sourceImage, boolean slimModel) {
        return slimModel && sourceImage.getWidth() == 64 && sourceImage.getHeight() == 64;
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

        copyLegacyLimb(normalizedImage, 0, 16);
        copyLegacyLimb(normalizedImage, 40, 32);
        return normalizedImage;
    }

    private void copyLegacyLimb(BufferedImage image, int sourceX, int targetX) {
        int sourceY = 16;
        int targetY = 48;
        copyMirroredArea(image, sourceX + 4, sourceY, 4, targetX + 4, targetY);
        copyMirroredArea(image, sourceX + 8, sourceY, 4, targetX + 8, targetY);
        copyMirroredArea(image, sourceX, sourceY + 4, 12, targetX + 8, targetY + 4);
        copyMirroredArea(image, sourceX + 4, sourceY + 4, 12, targetX + 4, targetY + 4);
        copyMirroredArea(image, sourceX + 8, sourceY + 4, 12, targetX, targetY + 4);
        copyMirroredArea(image, sourceX + 12, sourceY + 4, 12, targetX + 12, targetY + 4);
    }

    private void copyMirroredArea(BufferedImage image, int sourceX, int sourceY, int height, int targetX, int targetY) {
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < height; y++) {
                int color = image.getRGB(sourceX + (3 - x), sourceY + y);
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

                sourceX = mapVirtualX(sourceX, sourceRect.width(), localWidth, sourceRect.padMode());
                sourceX += sourceRect.x();
                sourceY += sourceRect.y();
                outputImage.setRGB(targetRect.x() + x, targetRect.y() + y, sourceImage.getRGB(sourceX, sourceY));
            }
        }
    }

    private int mapVirtualX(int virtualX, int sourceWidth, int virtualWidth, PadMode padMode) {
        if (sourceWidth <= 1 || virtualWidth <= 1 || sourceWidth == virtualWidth) {
            return Math.clamp(virtualX, 0, sourceWidth - 1);
        }

        int paddingWidth = virtualWidth - sourceWidth;
        int sourceX = switch (padMode) {
            case LEFT -> Math.max(0, virtualX - paddingWidth);
            case RIGHT -> Math.min(sourceWidth - 1, virtualX);
            case NONE -> (int) Math.round(virtualX * (sourceWidth - 1) / (double) (virtualWidth - 1));
        };
        return Math.clamp(sourceX, 0, sourceWidth - 1);
    }

    private byte[] writePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "png", outputStream);
        return outputStream.toByteArray();
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
            faceRect.virtualWidth(),
            faceRect.padMode()
        );
    }

    private boolean usesWideSlimArmAtlas(PlayerSkinPart skinPart, boolean slimModel) {
        return slimModel && (skinPart == PlayerSkinPart.RIGHT_ARM || skinPart == PlayerSkinPart.LEFT_ARM);
    }

    private BufferedImage expandSlimArmToWideAtlas(BufferedImage sourceImage, PlayerSkinPart skinPart) {
        BufferedImage expandedImage = copyImage(sourceImage);
        copyFaceMap(expandedImage, sourceImage, slimBaseFaces(skinPart), baseFaces(skinPart));
        copyFaceMap(expandedImage, sourceImage, slimOverlayFaces(skinPart), overlayFaces(skinPart));
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

    private static FaceMap baseFaces(PlayerSkinPart part) {
        return part(part).base();
    }

    private static FaceMap overlayFaces(PlayerSkinPart part) {
        return part(part).overlay();
    }

    private static FaceMap slimBaseFaces(PlayerSkinPart part) {
        return requireSlimFaces(part, part(part).slimBase());
    }

    private static FaceMap slimOverlayFaces(PlayerSkinPart part) {
        return requireSlimFaces(part, part(part).slimOverlay());
    }

    private static PartAtlas part(PlayerSkinPart part) {
        return PARTS.get(part);
    }

    private static FaceMap requireSlimFaces(PlayerSkinPart part, FaceMap faces) {
        if (faces == null) {
            throw new IllegalArgumentException(part + " does not have a slim atlas");
        }
        return faces;
    }

    private static FaceMap faces(
        int topX,
        int topY,
        int topWidth,
        int topHeight,
        int rightX,
        int sideY,
        int sideWidth,
        int sideHeight
    ) {
        return new FaceMap(
            new FaceRect(topX, topY, topWidth, topHeight),
            new FaceRect(topX + topWidth, topY, topWidth, topHeight),
            new FaceRect(rightX, sideY, sideWidth, sideHeight),
            new FaceRect(rightX + sideWidth, sideY, topWidth, sideHeight),
            new FaceRect(rightX + sideWidth + topWidth, sideY, sideWidth, sideHeight),
            new FaceRect(rightX + sideWidth * 2 + topWidth, sideY, topWidth, sideHeight)
        );
    }

    private static FaceMap slimRightArmFaces(int topY, int sideY) {
        return new FaceMap(
            slimRect(44, topY, 4, PadMode.LEFT),
            slimRect(47, topY, 4, PadMode.LEFT),
            new FaceRect(40, sideY, 4, 12),
            slimRect(44, sideY, 12, PadMode.LEFT),
            new FaceRect(47, sideY, 4, 12),
            slimRect(51, sideY, 12, PadMode.RIGHT)
        );
    }

    private static FaceMap slimLeftArmFaces(boolean overlay) {
        int topY = 48;
        int sideY = 52;
        int topX = overlay ? 52 : 36;
        int rightX = overlay ? 48 : 32;
        return new FaceMap(
            slimRect(topX, topY, 4, PadMode.RIGHT),
            slimRect(topX + 3, topY, 4, PadMode.RIGHT),
            new FaceRect(rightX, sideY, 4, 12),
            slimRect(topX, sideY, 12, PadMode.RIGHT),
            new FaceRect(topX + 3, sideY, 4, 12),
            slimRect(topX + 7, sideY, 12, PadMode.LEFT)
        );
    }

    private static FaceRect slimRect(int x, int y, int height, PadMode padMode) {
        return new FaceRect(x, y, 3, height, 4, padMode);
    }

    private record FaceTarget(int x, int y, int width, int height) {
    }

    private record FaceRect(int x, int y, int width, int height, int virtualWidth, PadMode padMode) {
        private FaceRect(int x, int y, int width, int height) {
            this(x, y, width, height, width, PadMode.NONE);
        }
    }

    private enum PadMode {
        NONE,
        LEFT,
        RIGHT
    }

    private record FaceMap(FaceRect top, FaceRect bottom, FaceRect right, FaceRect front, FaceRect left, FaceRect back) {
    }

    private record PartAtlas(FaceMap base, FaceMap overlay, FaceMap slimBase, FaceMap slimOverlay) {
    }

    final class PreparedSkin {
        private final BufferedImage normalizedImage;
        private final boolean slimModel;
        private final Map<PlayerSkinPart, BufferedImage> expandedArmImages = new EnumMap<>(PlayerSkinPart.class);

        private PreparedSkin(BufferedImage normalizedImage, boolean slimModel) {
            this.normalizedImage = normalizedImage;
            this.slimModel = slimModel;
        }

        private BufferedImage imageFor(PlayerSkinPart skinPart) {
            if (!usesWideSlimArmAtlas(skinPart, this.slimModel)) {
                return this.normalizedImage;
            }
            return this.expandedArmImages.computeIfAbsent(
                skinPart,
                part -> expandSlimArmToWideAtlas(this.normalizedImage, part)
            );
        }
    }
}
