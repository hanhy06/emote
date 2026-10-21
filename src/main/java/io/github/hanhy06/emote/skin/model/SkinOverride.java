package io.github.hanhy06.emote.skin.model;

import io.github.hanhy06.emote.util.Sha256;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HexFormat;

public final class SkinOverride {
    private final BufferedImage texture;
    private final boolean[] mask = new boolean[64 * 64];
    private final String hash;

    public SkinOverride(byte[] texturePng, byte[] maskPng) throws IOException {
        this.texture = PlayerSkinSource.readPng(texturePng);
        BufferedImage maskImage = PlayerSkinSource.readPng(maskPng);
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) {
            int color = maskImage.getRGB(x, y);
            if ((color >>> 24) == 0) continue;
            int rgb = color & 0x00ffffff;
            if (rgb != 0 && rgb != 0x00ffffff) throw new IOException("override mask must contain only black, white, or transparent pixels");
            this.mask[y * 64 + x] = rgb == 0x00ffffff;
        }
        var digest = Sha256.newDigest();
        digest.update(Sha256.newDigest().digest(texturePng));
        digest.update(Sha256.newDigest().digest(maskPng));
        this.hash = HexFormat.of().formatHex(digest.digest());
    }

    public String hash() { return this.hash; }

    public BufferedImage apply(BufferedImage source) {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) {
            int color = source.getRGB(x, y);
            if (this.mask[y * 64 + x]) {
                color = this.texture.getRGB(x, y);
                if (y < 16 && x < 32 || y >= 16 && y < 32 || y >= 48 && x >= 16 && x < 48) color |= 0xff000000;
            }
            image.setRGB(x, y, color);
        }
        return image;
    }
}
