package io.github.hanhy06.emote.skin;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerSkinPartTest {
	@Test
	void fromIdIgnoresTurkishLocaleCasing() {
		Locale previousLocale = Locale.getDefault();
		Locale.setDefault(Locale.forLanguageTag("tr-TR"));

		try {
			assertEquals(PlayerSkinPart.LEFT_ARM, PlayerSkinPart.fromId("EMOTE:LEFT_ARM"));
		} finally {
			Locale.setDefault(previousLocale);
		}
	}
}
