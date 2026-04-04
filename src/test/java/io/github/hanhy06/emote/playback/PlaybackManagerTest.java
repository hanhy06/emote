package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.emote.EmoteAnimation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaybackManagerTest {
	@Test
	void calculateStopTickUsesMaxValueForLoopAnimation() {
		assertEquals(Long.MAX_VALUE, PlaybackManager.calculateStopTick(100L, EmoteAnimation.createLoop("default", 20)));
	}

	@Test
	void calculateStopTickUsesKeyframesForNormalAnimation() {
		assertEquals(148L, PlaybackManager.calculateStopTick(100L, new EmoteAnimation("default", 20)));
	}
}
