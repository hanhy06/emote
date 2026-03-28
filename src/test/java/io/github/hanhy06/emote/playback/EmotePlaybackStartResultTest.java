package io.github.hanhy06.emote.playback;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmotePlaybackStartResultTest {
	@Test
	void successHasNoErrorMessage() {
		assertTrue(EmotePlaybackStartResult.SUCCESS.isSuccess());
	}

	@Test
	void failureTrimsErrorMessage() {
		EmotePlaybackStartResult result = EmotePlaybackStartResult.failure(" Datapack not loaded. ");

		assertFalse(result.isSuccess());
		assertEquals("Datapack not loaded.", result.errorMessage());
	}
}
