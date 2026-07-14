package io.github.hanhy06.emote.skin;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerSkinBakerTest {
	private static final FaceSample TOP = new FaceSample(12, 4);
	private static final FaceSample BOTTOM = new FaceSample(20, 4);
	private static final FaceSample RIGHT = new FaceSample(4, 12);
	private static final FaceSample FRONT = new FaceSample(12, 12);
	private static final FaceSample LEFT = new FaceSample(20, 12);
	private static final FaceSample BACK = new FaceSample(28, 12);

	private final PlayerSkinBaker playerSkinBaker = new PlayerSkinBaker();

	@Test
	void bakeHeadKeepsHeadTextureDirection() throws IOException {
		BufferedImage sourceImage = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		fillRect(sourceImage, 8, 0, 8, 8, Color.RED);
		fillRect(sourceImage, 16, 0, 8, 8, Color.GREEN);
		fillRect(sourceImage, 0, 8, 8, 8, Color.BLUE);
		fillRect(sourceImage, 8, 8, 8, 8, Color.YELLOW);
		fillRect(sourceImage, 16, 8, 8, 8, Color.MAGENTA);
		fillRect(sourceImage, 24, 8, 8, 8, Color.CYAN);

		BufferedImage bakedImage = bake(sourceImage, PlayerSkinPart.HEAD, false);

		assertFaceColors(bakedImage, Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.MAGENTA, Color.CYAN);
	}

	@Test
	void bakeBodyKeepsBodyTextureDirection() throws IOException {
		BufferedImage sourceImage = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		fillRect(sourceImage, 20, 16, 8, 4, Color.RED);
		fillRect(sourceImage, 28, 16, 8, 4, Color.GREEN);
		fillRect(sourceImage, 16, 20, 4, 12, Color.BLUE);
		fillRect(sourceImage, 20, 20, 8, 12, Color.YELLOW);
		fillRect(sourceImage, 28, 20, 4, 12, Color.MAGENTA);
		fillRect(sourceImage, 32, 20, 8, 12, Color.CYAN);

		BufferedImage bakedImage = bake(sourceImage, PlayerSkinPart.BODY, false);

		assertFaceColors(bakedImage, Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.MAGENTA, Color.CYAN);
	}

	@Test
	void bakeBodyKeepsFacePixelsUnrotated() throws IOException {
		BufferedImage sourceImage = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		fillDirectionalFace(sourceImage, 20, 16, 8, 4);
		fillDirectionalFace(sourceImage, 28, 16, 8, 4);
		fillDirectionalFace(sourceImage, 16, 20, 4, 12);
		fillDirectionalFace(sourceImage, 20, 20, 8, 12);
		fillDirectionalFace(sourceImage, 28, 20, 4, 12);
		fillDirectionalFace(sourceImage, 32, 20, 8, 12);

		BufferedImage bakedImage = bake(sourceImage, PlayerSkinPart.BODY, false);

		assertDirectionalFace(bakedImage, 8, 0, 8, 8);
		assertDirectionalFace(bakedImage, 16, 0, 8, 8);
		assertDirectionalFace(bakedImage, 0, 8, 8, 8);
		assertDirectionalFace(bakedImage, 8, 8, 8, 8);
		assertDirectionalFace(bakedImage, 16, 8, 8, 8);
		assertDirectionalFace(bakedImage, 24, 8, 8, 8);
	}

	@Test
	void bakeWideRightArmKeepsRightArmTextureDirection() throws IOException {
		BufferedImage sourceImage = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		fillRect(sourceImage, 44, 16, 4, 4, Color.RED);
		fillRect(sourceImage, 48, 16, 4, 4, Color.GREEN);
		fillRect(sourceImage, 40, 20, 4, 12, Color.BLUE);
		fillRect(sourceImage, 44, 20, 4, 12, Color.YELLOW);
		fillRect(sourceImage, 48, 20, 4, 12, Color.MAGENTA);
		fillRect(sourceImage, 52, 20, 4, 12, Color.CYAN);

		BufferedImage bakedImage = bake(sourceImage, PlayerSkinPart.RIGHT_ARM, false);

		assertFaceColors(bakedImage, Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.MAGENTA, Color.CYAN);
	}

	@Test
	void bakeSlimRightArmKeepsRightArmTexture() throws IOException {
		BufferedImage sourceImage = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		fillRect(sourceImage, 44, 16, 3, 4, Color.RED);
		fillRect(sourceImage, 47, 16, 3, 4, Color.GREEN);
		fillRect(sourceImage, 40, 20, 4, 12, Color.RED);
		fillRect(sourceImage, 44, 20, 3, 12, Color.GREEN);
		fillRect(sourceImage, 47, 20, 4, 12, Color.BLUE);
		fillRect(sourceImage, 51, 20, 3, 12, Color.YELLOW);
		fillRect(sourceImage, 47, 16, 3, 4, Color.GREEN);
		fillRect(sourceImage, 44, 32, 3, 4, Color.MAGENTA);
		fillRect(sourceImage, 47, 32, 3, 4, Color.ORANGE);
		fillRect(sourceImage, 40, 36, 4, 12, Color.CYAN);
		fillRect(sourceImage, 44, 36, 3, 12, Color.PINK);
		fillRect(sourceImage, 47, 36, 4, 12, Color.GRAY);
		fillRect(sourceImage, 51, 36, 3, 12, Color.LIGHT_GRAY);

		BufferedImage bakedImage = bake(sourceImage, PlayerSkinPart.RIGHT_ARM, true);

		assertFaceColors(bakedImage, Color.RED, Color.GREEN, Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW);
	}

	@Test
	void bakeSlimLeftArmKeepsLeftArmTextureDirection() throws IOException {
		BufferedImage sourceImage = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		fillRect(sourceImage, 36, 48, 3, 4, Color.RED);
		fillRect(sourceImage, 39, 48, 3, 4, Color.GREEN);
		fillRect(sourceImage, 32, 52, 4, 12, Color.MAGENTA);
		fillRect(sourceImage, 36, 52, 3, 12, Color.ORANGE);
		fillRect(sourceImage, 39, 52, 4, 12, Color.CYAN);
		fillRect(sourceImage, 43, 52, 3, 12, Color.PINK);

		BufferedImage bakedImage = bake(sourceImage, PlayerSkinPart.LEFT_ARM, true);

		assertFaceColors(bakedImage, Color.RED, Color.GREEN, Color.CYAN, Color.ORANGE, Color.MAGENTA, Color.PINK);
	}

	@Test
	void bakeLeftLegKeepsLeftLegTextureDirection() throws IOException {
		BufferedImage sourceImage = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		fillRect(sourceImage, 20, 48, 4, 4, Color.RED);
		fillRect(sourceImage, 24, 48, 4, 4, Color.GREEN);
		fillRect(sourceImage, 16, 52, 4, 12, Color.BLUE);
		fillRect(sourceImage, 20, 52, 4, 12, Color.YELLOW);
		fillRect(sourceImage, 24, 52, 4, 12, Color.MAGENTA);
		fillRect(sourceImage, 28, 52, 4, 12, Color.CYAN);

		BufferedImage bakedImage = bake(sourceImage, PlayerSkinPart.LEFT_LEG, false);

		assertFaceColors(bakedImage, Color.RED, Color.GREEN, Color.MAGENTA, Color.YELLOW, Color.BLUE, Color.CYAN);
	}

	@Test
	void bakeLegacySkinIgnoresSlimFlag() throws IOException {
		BufferedImage sourceImage = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
		fillRect(sourceImage, 44, 16, 4, 4, Color.RED);
		fillRect(sourceImage, 48, 16, 4, 4, Color.GREEN);
		fillRect(sourceImage, 40, 20, 4, 12, Color.BLUE);
		fillRect(sourceImage, 44, 20, 4, 12, Color.YELLOW);
		fillRect(sourceImage, 48, 20, 4, 12, Color.MAGENTA);
		fillRect(sourceImage, 52, 20, 4, 12, Color.CYAN);

		BufferedImage classicBakedImage = bake(sourceImage, PlayerSkinPart.RIGHT_ARM, false);
		BufferedImage slimBakedImage = bake(sourceImage, PlayerSkinPart.RIGHT_ARM, true);

		assertEquals(classicBakedImage.getRGB(TOP.x(), TOP.y()), slimBakedImage.getRGB(TOP.x(), TOP.y()));
		assertEquals(classicBakedImage.getRGB(BOTTOM.x(), BOTTOM.y()), slimBakedImage.getRGB(BOTTOM.x(), BOTTOM.y()));
		assertEquals(classicBakedImage.getRGB(RIGHT.x(), RIGHT.y()), slimBakedImage.getRGB(RIGHT.x(), RIGHT.y()));
		assertEquals(classicBakedImage.getRGB(FRONT.x(), FRONT.y()), slimBakedImage.getRGB(FRONT.x(), FRONT.y()));
		assertEquals(classicBakedImage.getRGB(LEFT.x(), LEFT.y()), slimBakedImage.getRGB(LEFT.x(), LEFT.y()));
		assertEquals(classicBakedImage.getRGB(BACK.x(), BACK.y()), slimBakedImage.getRGB(BACK.x(), BACK.y()));
	}

	private BufferedImage bake(BufferedImage sourceImage, PlayerSkinPart skinPart, boolean slimModel) throws IOException {
		return ImageIO.read(new ByteArrayInputStream(this.playerSkinBaker.bake(sourceImage, skinPart, PlayerSkinSegment.FULL, slimModel)));
	}

	private void assertFaceColors(
			BufferedImage image,
			Color topColor,
			Color bottomColor,
			Color rightColor,
			Color frontColor,
			Color leftColor,
			Color backColor
	) {
		assertEquals(topColor.getRGB(), image.getRGB(TOP.x(), TOP.y()));
		assertEquals(bottomColor.getRGB(), image.getRGB(BOTTOM.x(), BOTTOM.y()));
		assertEquals(rightColor.getRGB(), image.getRGB(RIGHT.x(), RIGHT.y()));
		assertEquals(frontColor.getRGB(), image.getRGB(FRONT.x(), FRONT.y()));
		assertEquals(leftColor.getRGB(), image.getRGB(LEFT.x(), LEFT.y()));
		assertEquals(backColor.getRGB(), image.getRGB(BACK.x(), BACK.y()));
	}

	private void fillRect(BufferedImage image, int x, int y, int width, int height, Color color) {
		for (int dx = 0; dx < width; dx++) {
			for (int dy = 0; dy < height; dy++) {
				image.setRGB(x + dx, y + dy, color.getRGB());
			}
		}
	}

	private void fillDirectionalFace(BufferedImage image, int x, int y, int width, int height) {
		for (int dx = 0; dx < width; dx++) {
			for (int dy = 0; dy < height; dy++) {
				Color color;
				if (dx < width / 2 && dy < height / 2) {
					color = Color.RED;
				} else if (dy < height / 2) {
					color = Color.GREEN;
				} else if (dx < width / 2) {
					color = Color.BLUE;
				} else {
					color = Color.YELLOW;
				}

				image.setRGB(x + dx, y + dy, color.getRGB());
			}
		}
	}

	private void assertDirectionalFace(BufferedImage image, int x, int y, int width, int height) {
		assertEquals(Color.RED.getRGB(), image.getRGB(x, y));
		assertEquals(Color.GREEN.getRGB(), image.getRGB(x + width - 1, y));
		assertEquals(Color.BLUE.getRGB(), image.getRGB(x, y + height - 1));
		assertEquals(Color.YELLOW.getRGB(), image.getRGB(x + width - 1, y + height - 1));
	}

	private record FaceSample(int x, int y) {
	}
}
