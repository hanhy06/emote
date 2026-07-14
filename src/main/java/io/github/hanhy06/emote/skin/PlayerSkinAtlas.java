package io.github.hanhy06.emote.skin;

import java.util.Map;

final class PlayerSkinAtlas {
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
                    slimLeftArmFaces(48, 52),
                    slimLeftArmFaces(48, 52, true)
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

    private PlayerSkinAtlas() {
    }

    static FaceMap baseFaces(PlayerSkinPart part) {
        return part(part).base();
    }

    static FaceMap overlayFaces(PlayerSkinPart part) {
        return part(part).overlay();
    }

    static FaceMap slimBaseFaces(PlayerSkinPart part) {
        return requireSlimFaces(part, part(part).slimBase());
    }

    static FaceMap slimOverlayFaces(PlayerSkinPart part) {
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
                slimRect(44, topY, 3, 4, PadMode.LEFT),
                slimRect(47, topY, 3, 4, PadMode.LEFT),
                new FaceRect(40, sideY, 4, 12),
                slimRect(44, sideY, 3, 12, PadMode.LEFT),
                new FaceRect(47, sideY, 4, 12),
                slimRect(51, sideY, 3, 12, PadMode.RIGHT)
        );
    }

    private static FaceMap slimLeftArmFaces(int topY, int sideY) {
        return slimLeftArmFaces(topY, sideY, false);
    }

    private static FaceMap slimLeftArmFaces(int topY, int sideY, boolean overlay) {
        int topX = overlay ? 52 : 36;
        int rightX = overlay ? 48 : 32;
        return new FaceMap(
                slimRect(topX, topY, 3, 4, PadMode.RIGHT),
                slimRect(topX + 3, topY, 3, 4, PadMode.RIGHT),
                new FaceRect(rightX, sideY, 4, 12),
                slimRect(topX, sideY, 3, 12, PadMode.RIGHT),
                new FaceRect(topX + 3, sideY, 4, 12),
                slimRect(topX + 7, sideY, 3, 12, PadMode.LEFT)
        );
    }

    private static FaceRect slimRect(int x, int y, int width, int height, PadMode padMode) {
        return new FaceRect(x, y, width, height, width + 1, padMode);
    }

    record FaceRect(int x, int y, int width, int height, int virtualWidth, PadMode padMode) {
        FaceRect(int x, int y, int width, int height) {
            this(x, y, width, height, width, PadMode.NONE);
        }
    }

    enum PadMode {
        NONE,
        LEFT,
        RIGHT
    }

    record FaceMap(FaceRect top, FaceRect bottom, FaceRect right, FaceRect front, FaceRect left, FaceRect back) {
    }

    private record PartAtlas(
            FaceMap base,
            FaceMap overlay,
            FaceMap slimBase,
            FaceMap slimOverlay
    ) {
    }
}
