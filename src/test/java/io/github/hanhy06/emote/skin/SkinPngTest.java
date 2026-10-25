package io.github.hanhy06.emote.skin;

import io.github.hanhy06.emote.skin.model.PlayerSkinSource;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

class SkinPngTest {
    @Test
    void rejectsOversizedDimensionsFromTheHeaderBeforeReadingPixelData() throws Exception {
        var output = new ByteArrayOutputStream();
        var png = new DataOutputStream(output);
        png.writeLong(0x89504e470d0a1a0aL);
        png.writeInt(13);
        var headerBytes = new ByteArrayOutputStream();
        var header = new DataOutputStream(headerBytes);
        header.writeInt(0x49484452);
        header.writeInt(4096);
        header.writeInt(4096);
        header.write(new byte[]{8, 6, 0, 0, 0});
        png.write(headerBytes.toByteArray());
        var crc = new CRC32();
        crc.update(headerBytes.toByteArray());
        png.writeInt((int) crc.getValue());

        IOException exception = assertThrows(IOException.class, () -> PlayerSkinSource.readPng(output.toByteArray(), true));
        assertEquals("skin image must be 64x32 or 64x64", exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    void permitsLegacyOnlyWhenRequestedAndPreservesDecodedPixels() throws Exception {
        for (int height : new int[]{32, 64}) {
            var image = new BufferedImage(64, height, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(8, 8, 0x40223344);
            var output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            byte[] png = output.toByteArray();
            BufferedImage decoded = PlayerSkinSource.readPng(png, true);
            assertEquals(height, decoded.getHeight());
            assertEquals(0x40223344, decoded.getRGB(8, 8));
            if (height == 32) {
                assertThrows(IOException.class, () -> PlayerSkinSource.readPng(png));
            } else {
                assertArrayEquals(png, PlayerSkinSource.fromPng(png).png());
                assertEquals(0x40223344, PlayerSkinSource.readPng(png).getRGB(8, 8));
            }
        }
    }
}
