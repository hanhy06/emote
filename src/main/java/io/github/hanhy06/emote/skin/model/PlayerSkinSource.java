package io.github.hanhy06.emote.skin.model;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.minecraft.SessionService;
import com.mojang.authlib.properties.Property;
import org.jspecify.annotations.Nullable;
import io.github.hanhy06.emote.util.Sha256;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HexFormat;

import java.util.Objects;
import java.util.UUID;

public record PlayerSkinSource(
    UUID playerUuid,
    String playerName,
    String textureHash,
    String textureUrl,
    boolean slimModel,
    @Nullable byte[] png
) {
    public PlayerSkinSource(UUID playerUuid, String playerName, String textureHash, String textureUrl, boolean slimModel) {
        this(playerUuid, playerName, textureHash, textureUrl, slimModel, null);
    }

    public PlayerSkinSource {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(textureHash, "textureHash");
        Objects.requireNonNull(textureUrl, "textureUrl");
    }

    public record Key(String textureHash, boolean slimModel) {}

    public static PlayerSkinSource fromPng(byte[] png) throws IOException {
        if (png.length > 1_048_576 || png.length < 8 || png[0] != (byte) 0x89 || png[1] != 0x50 || png[2] != 0x4e || png[3] != 0x47) {
            throw new IOException("default skin must be a PNG no larger than 1 MiB");
        }
        var image = ImageIO.read(new ByteArrayInputStream(png));
        if (image == null || image.getWidth() != 64 || image.getHeight() != 64) throw new IOException("default skin must be 64x64 with wide UVs");
        String hash = HexFormat.of().formatHex(Sha256.newDigest().digest(png));
        return new PlayerSkinSource(UUID.nameUUIDFromBytes(png), "embedded", hash, "", false, png);
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
