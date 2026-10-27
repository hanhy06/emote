package io.github.hanhy06.emote.skin.model;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.minecraft.SessionService;
import com.mojang.authlib.properties.Property;
import org.jspecify.annotations.Nullable;
import io.github.hanhy06.emote.util.Sha256;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HexFormat;

import java.util.Objects;
import java.util.UUID;

public record PlayerSkinSource(
    UUID playerUuid,
    String playerName,
    String textureHash,
    String textureUrl,
    boolean slimModel,
    @Nullable byte[] png,
    @Nullable SkinOverride override
) {
    public static final int MAX_PNG_BYTES = 1_048_576;

    public PlayerSkinSource(UUID playerUuid, String playerName, String textureHash, String textureUrl, boolean slimModel) {
        this(playerUuid, playerName, textureHash, textureUrl, slimModel, null, null);
    }

    public PlayerSkinSource {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(textureHash, "textureHash");
        Objects.requireNonNull(textureUrl, "textureUrl");
    }

    public record Key(String textureHash, boolean slimModel) {}

    public static PlayerSkinSource fromPng(byte[] png) {
        String hash = HexFormat.of().formatHex(Sha256.newDigest().digest(png));
        return new PlayerSkinSource(UUID.nameUUIDFromBytes(png), "embedded", hash, "", false, png, null);
    }

    public PlayerSkinSource withOverride(@Nullable SkinOverride override) {
        if (override == null) return this;
        var digest = Sha256.newDigest();
        digest.update(this.textureHash.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(override.hash().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String hash = HexFormat.of().formatHex(digest.digest());
        return new PlayerSkinSource(this.playerUuid, this.playerName, hash, this.textureUrl, this.slimModel, this.png, override);
    }

    public static BufferedImage readPng(byte[] png) throws IOException {
        return readPng(png, false);
    }

    public static BufferedImage readPng(byte[] png, boolean allowLegacy) throws IOException {
        if (png.length > MAX_PNG_BYTES || png.length < 8 || !Arrays.equals(png, 0, 8, new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}, 0, 8)) {
            throw new IOException("skin image must be a PNG no larger than 1 MiB");
        }
        var reader = ImageIO.getImageReadersByFormatName("png").next();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(png))) {
            reader.setInput(input, true, true);
            int width = reader.getWidth(0), height = reader.getHeight(0);
            if (width != 64 || (height != 64 && !(allowLegacy && height == 32))) {
                throw new IOException(allowLegacy ? "skin image must be 64x32 or 64x64" : "skin image must be 64x64 with wide UVs");
            }
            return reader.read(0);
        } finally {
            reader.dispose();
        }
    }

    public static @Nullable PlayerSkinSource fromProfile(GameProfile profile, SessionService sessionService) {
        Property packedTextures = sessionService.getPackedTextures(profile);
        if (packedTextures == null) return null;
        MinecraftProfileTextures textures = sessionService.unpackTextures(packedTextures);
        MinecraftProfileTexture skinTexture = textures.skin();
        if (skinTexture == null) return null;
        return new PlayerSkinSource(profile.id(), profile.name(), skinTexture.getHash(), skinTexture.getUrl(),
            "slim".equalsIgnoreCase(skinTexture.getMetadata("model")));
    }
}
