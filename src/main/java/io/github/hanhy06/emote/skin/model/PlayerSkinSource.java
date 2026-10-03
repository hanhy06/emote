package io.github.hanhy06.emote.skin.model;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.properties.Property;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

public record PlayerSkinSource(
    UUID playerUuid,
    String playerName,
    String textureHash,
    String textureUrl,
    boolean slimModel
) {
    public PlayerSkinSource {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(textureHash, "textureHash");
        Objects.requireNonNull(textureUrl, "textureUrl");
    }

    public record Key(String textureHash, boolean slimModel) {}

    public static @Nullable PlayerSkinSource fromProfile(GameProfile profile, MinecraftSessionService sessionService) {
        Property packedTextures = sessionService.getPackedTextures(profile);
        if (packedTextures == null) return null;
        MinecraftProfileTextures textures = sessionService.unpackTextures(packedTextures);
        MinecraftProfileTexture skinTexture = textures.skin();
        if (skinTexture == null) return null;
        return new PlayerSkinSource(profile.id(), profile.name(), skinTexture.getHash(), skinTexture.getUrl(),
            "slim".equalsIgnoreCase(skinTexture.getMetadata("model")));
    }
}
