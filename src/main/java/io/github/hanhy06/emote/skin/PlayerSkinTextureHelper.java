package io.github.hanhy06.emote.skin;

import com.google.common.collect.ImmutableMultimap;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.world.item.component.ResolvableProfile;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

public final class PlayerSkinTextureHelper {
    private PlayerSkinTextureHelper() {
    }

    public static ResolvableProfile createProfile(String profileName, String textureUrl) {
        UUID profileId = UUID.nameUUIDFromBytes(textureUrl.getBytes(StandardCharsets.UTF_8));
        PropertyMap properties = new PropertyMap(ImmutableMultimap.of(
            "textures",
            new Property("textures", encodeTextureValue(profileId, profileName, textureUrl))
        ));
        GameProfile profile = new GameProfile(profileId, profileName, properties);
        return ResolvableProfile.createResolved(profile);
    }

    private static String encodeTextureValue(UUID profileId, String profileName, String textureUrl) {
        JsonObject rootObject = new JsonObject();
        rootObject.addProperty("timestamp", System.currentTimeMillis());
        rootObject.addProperty("profileId", profileId.toString().replace("-", ""));
        rootObject.addProperty("profileName", profileName);

        JsonObject texturesObject = new JsonObject();
        JsonObject skinObject = new JsonObject();
        skinObject.addProperty("url", textureUrl);
        texturesObject.add("SKIN", skinObject);
        rootObject.add("textures", texturesObject);
        return Base64.getEncoder().encodeToString(rootObject.toString().getBytes(StandardCharsets.UTF_8));
    }
}
