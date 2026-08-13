package io.github.hanhy06.emote.skin;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerHeadProfileFactoryTest {
    @Test
    void createsProfileWithMineSkinTextureUrl() {
        String textureUrl = "https://textures.minecraft.net/texture/cached";
        var profile = PlayerHeadProfileFactory.createProfile(textureUrl).partialProfile();
        String encodedTextures = profile.properties().get("textures").iterator().next().value();
        String decodedTextures = new String(Base64.getDecoder().decode(encodedTextures), StandardCharsets.UTF_8);

        assertEquals(
            textureUrl,
            JsonParser.parseString(decodedTextures)
                .getAsJsonObject()
                .getAsJsonObject("textures")
                .getAsJsonObject("SKIN")
                .get("url")
                .getAsString()
        );
    }
}
