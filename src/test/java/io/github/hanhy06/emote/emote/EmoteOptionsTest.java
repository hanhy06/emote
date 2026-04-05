package io.github.hanhy06.emote.emote;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmoteOptionsTest {
	@Test
	void normalizeUsesCanonicalOptionNames() {
		assertEquals("visible_player loop", EmoteOptions.normalize(" Visible-Player   loop   sync   visible "));
	}

	@Test
	void parseReadsLoopAndVisiblePlayerOptions() {
		EmoteOptions options = EmoteOptions.parse("visible-player loop");

		assertTrue(options.visiblePlayer());
		assertTrue(options.loop());
	}

	@Test
	void parseIgnoresUnknownOptions() {
		EmoteOptions options = EmoteOptions.parse("sync");

		assertFalse(options.visiblePlayer());
		assertFalse(options.loop());
	}
}
