package io.github.hanhy06.emote.emote;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class EmoteRegistryTest {
	@Test
	void findDefinitionByCommandNameIgnoresTurkishLocaleCasing() {
		Locale previousLocale = Locale.getDefault();
		Locale.setDefault(Locale.forLanguageTag("tr-TR"));

		try {
			EmoteRegistry registry = new EmoteRegistry();
			registry.replaceDefinitions(List.of(new EmoteDefinition(
				"idle",
				"Idle",
				"Idle animation",
				"idle",
				"default",
				Path.of("test-pack"),
				1,
				List.of(new EmoteAnimation("default", 20)),
				List.of()
			)));

			assertNotNull(registry.findDefinitionByCommandName("IDLE"));
		} finally {
			Locale.setDefault(previousLocale);
		}
	}

	@Test
	void getPlayNamesKeepsFirstCommandAliasOrder() {
		EmoteRegistry registry = new EmoteRegistry();
		registry.replaceDefinitions(List.of(
			createDefinition("alpha", "shared"),
			createDefinition("beta", "shared")
		));

		assertEquals(List.of("shared", "alpha", "beta"), registry.getPlayNames());
	}

	private EmoteDefinition createDefinition(String namespace, String commandName) {
		return new EmoteDefinition(
			namespace,
			namespace,
			namespace + " description",
			commandName,
			"default",
			Path.of(namespace + "-pack"),
			1,
			List.of(new EmoteAnimation("default", 20)),
			List.of()
		);
	}
}
