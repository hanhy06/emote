package io.github.hanhy06.emote.skin;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerSkinPartTest {
	@Test
	void fromIdIgnoresTurkishLocaleCasing() {
		Locale previousLocale = Locale.getDefault();
		Locale.setDefault(Locale.forLanguageTag("tr-TR"));

		try {
			assertTrue(PlayerSkinPart.fromId("EMOTE:LEFT_ARM").isPresent());
			assertEquals(PlayerSkinPart.LEFT_ARM, PlayerSkinPart.fromId("EMOTE:LEFT_ARM").orElseThrow());
		} finally {
			Locale.setDefault(previousLocale);
		}
	}
}
