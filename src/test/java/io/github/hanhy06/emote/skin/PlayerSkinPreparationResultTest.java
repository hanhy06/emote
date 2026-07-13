package io.github.hanhy06.emote.skin;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerSkinPreparationResultTest {
    @Test
    void readyResultCarriesPreparedSkin() {
        PreparedPlayerSkin skin = new PreparedPlayerSkin("hash", false, Map.of());
        PlayerSkinPreparationResult result = PlayerSkinPreparationResult.ready(skin);

        assertTrue(result.isReady());
        assertEquals(skin, result.preparedSkin());
        assertNull(result.errorMessage());
    }

    @Test
    void failureResultNormalizesMessage() {
        PlayerSkinPreparationResult result = PlayerSkinPreparationResult.failure("  preparing  ");

        assertFalse(result.isReady());
        assertEquals("preparing", result.errorMessage());
        assertNull(result.preparedSkin());
    }
}
