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
	public static final String HTTP_PATH_PREFIX = "/emote/skin/";
	public static final String PNG_PATH_SUFFIX = ".png";
	private static final String TEXTURE_TOKEN_VERSION = "v25";

	private PlayerSkinTextureHelper() {
	}

	public static String buildTextureToken(String textureHash, boolean slimModel, PlayerSkinPart skinPart, PlayerSkinSegment skinSegment) {
		return TEXTURE_TOKEN_VERSION
				+ "-"
				+ textureHash.toLowerCase(java.util.Locale.ROOT)
				+ "-"
				+ (slimModel ? "slim" : "wide")
				+ "-"
				+ skinPart.id()
				+ "-"
				+ skinSegment.id();
	}

	public static String buildTextureUrl(String baseUrl, String textureToken) {
		return baseUrl + HTTP_PATH_PREFIX + textureToken + PNG_PATH_SUFFIX;
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
