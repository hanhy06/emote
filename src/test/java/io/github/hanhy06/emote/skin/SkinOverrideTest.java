package io.github.hanhy06.emote.skin;

import io.github.hanhy06.emote.skin.model.PlayerSkinPart;
import io.github.hanhy06.emote.skin.model.PlayerSkinRegion;
import io.github.hanhy06.emote.skin.model.SkinOverride;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class SkinOverrideTest {
    @Test
    void replacesBothLayersAfterSlimAndLegacyNormalizationWithoutBlendingOrMutatingTheSource() throws Exception {
        BufferedImage source = image(64, 0xff789abc);
        source.setRGB(44, 20, 0xffff0000);
        source.setRGB(45, 20, 0xff00ff00);
        source.setRGB(46, 20, 0xff0000ff);
        source.setRGB(47, 20, 0);
        BufferedImage texture = image(64, 0);
        BufferedImage mask = image(64, 0xff000000);
        texture.setRGB(8, 8, 0x40223344);
        texture.setRGB(41, 8, 0x40556677);
        texture.setRGB(47, 20, 0xffabcdef);
        texture.setRGB(39, 52, 0xffaabbcc);
        for (int[] point : new int[][]{{8, 8}, {40, 8}, {41, 8}, {47, 20}, {39, 52}}) mask.setRGB(point[0], point[1], 0xffffffff);
        mask.setRGB(42, 8, 0x00ffffff);
        var override = new SkinOverride(png(texture), png(mask));
        var baker = new PlayerSkinBaker();
        var prepared = baker.prepare(source, true, override);
        BufferedImage head = ImageIO.read(new ByteArrayInputStream(baker.bake(prepared, new PlayerSkinRegion(PlayerSkinPart.HEAD, 0, 1))));
        assertEquals(0xff223344, head.getRGB(8, 8));
        assertEquals(0, head.getRGB(40, 8));
        assertEquals(0x40556677, head.getRGB(41, 8));
        assertEquals(0xff789abc, head.getRGB(9, 8));
        assertEquals(0xff789abc, head.getRGB(42, 8));
        BufferedImage arm = ImageIO.read(new ByteArrayInputStream(baker.bake(prepared, new PlayerSkinRegion(PlayerSkinPart.RIGHT_ARM, 0, 1))));
        assertEquals(0xffff0000, arm.getRGB(8, 8));
        assertEquals(0xffabcdef, arm.getRGB(15, 8));
        assertEquals(0, source.getRGB(47, 20));
        assertEquals(0xff789abc, source.getRGB(40, 8));

        prepared = baker.prepare(image(32, 0xff123456), false, override);
        BufferedImage leftArm = ImageIO.read(new ByteArrayInputStream(baker.bake(prepared, new PlayerSkinRegion(PlayerSkinPart.LEFT_ARM, 0, 1))));
        assertEquals(0xffaabbcc, leftArm.getRGB(15, 8));
        assertEquals(0xff123456, leftArm.getRGB(8, 8));
    }

    private static BufferedImage image(int height, int color) {
        var image = new BufferedImage(64, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < 64; x++) image.setRGB(x, y, color);
        return image;
    }

    private static byte[] png(BufferedImage image) throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
