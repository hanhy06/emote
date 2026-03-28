package io.github.hanhy06.emote.emote;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmoteDefinitionTest {
	@Test
	void createDisplayNameUsesBaseNameForDefaultAnimation() {
		EmoteDefinition definition = createDefinition();

		assertEquals("Wave", definition.createDisplayName("default"));
	}

	@Test
	void createDisplayDescriptionAppendsAnimationForAlternateAnimation() {
		EmoteDefinition definition = createDefinition();

		assertEquals("Friendly wave (fast)", definition.createDisplayDescription("fast"));
	}

	private EmoteDefinition createDefinition() {
		return new EmoteDefinition(
			"wave",
			"Wave",
			"Friendly wave",
			"wave",
			"default",
			Path.of("test-pack"),
			1,
			List.of(
				new EmoteAnimation("default", 20),
				new EmoteAnimation("fast", 10)
			),
			List.of()
		);
	}
}
