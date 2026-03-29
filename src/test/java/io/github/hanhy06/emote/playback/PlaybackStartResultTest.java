package io.github.hanhy06.emote.playback;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackStartResultTest {
	@Test
	void successHasNoErrorMessage() {
		assertTrue(PlaybackStartResult.SUCCESS.isSuccess());
	}

	@Test
	void failureTrimsErrorMessage() {
		PlaybackStartResult result = PlaybackStartResult.failure(" Datapack not loaded. ");

		assertFalse(result.isSuccess());
		assertEquals("Datapack not loaded.", result.errorMessage());
	}
}
