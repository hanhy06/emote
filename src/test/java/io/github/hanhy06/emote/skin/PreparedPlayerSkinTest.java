package io.github.hanhy06.emote.skin;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreparedPlayerSkinTest {
    private static final EmoteSkinPart HEAD = new EmoteSkinPart(
        "head",
        PlayerSkinPart.HEAD,
        PlayerSkinSegment.FULL
    );
    private static final EmoteSkinPart BODY = new EmoteSkinPart(
        "body",
        PlayerSkinPart.BODY,
        PlayerSkinSegment.FULL
    );

    @Test
    void requiresEveryRequestedTexture() {
        PreparedPlayerSkin preparedSkin = new PreparedPlayerSkin(Map.of(
            new PlayerSkinTextureKey(PlayerSkinPart.HEAD, PlayerSkinSegment.FULL),
            "https://textures.example/head"
        ));

        assertTrue(preparedSkin.containsAll(List.of(HEAD)));
        assertFalse(preparedSkin.containsAll(List.of(HEAD, BODY)));
    }
}
