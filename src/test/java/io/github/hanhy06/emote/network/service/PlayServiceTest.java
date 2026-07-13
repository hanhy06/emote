package io.github.hanhy06.emote.network.service;

import io.github.hanhy06.emote.emote.EmoteDefinition;
import io.github.hanhy06.emote.emote.EmoteRegistry;
import io.github.hanhy06.emote.emote.PlayableEmoteService;
import io.github.hanhy06.emote.permission.PermissionService;
import io.github.hanhy06.emote.playback.data.PlaybackStartResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayServiceTest {
	@Test
	void playReturnsSuccess() {
		PlayService service = new PlayService(
			createPlayableEmoteService(),
			(player, selection) -> PlaybackStartResult.SUCCESS
		);

		PlayResult result = service.play(null, "wave");

		assertTrue(result.isSuccess());
	}

	@Test
	void playReturnsPlaybackFailure() {
		PlayService service = new PlayService(
			createPlayableEmoteService(),
			(player, selection) -> PlaybackStartResult.failure(" Datapack not loaded. ")
		);

		PlayResult result = service.play(null, "wave");

		assertFalse(result.isSuccess());
		assertEquals("Datapack not loaded.", result.errorMessage());
	}

	private PlayableEmoteService createPlayableEmoteService() {
		EmoteRegistry registry = new EmoteRegistry();
		registry.replaceDefinitions(List.of(new EmoteDefinition(
			"wave",
			"Wave",
			"Friendly wave",
			"wave",
			"a/default/play_anim_loop",
			true,
			Path.of("wave-pack"),
			1,
			List.of()
		)));
		return new PlayableEmoteService(registry, new PermissionService() {
			@Override
			public boolean canPlay(net.minecraft.server.level.ServerPlayer player, String namespace) {
				return true;
			}
		});
	}
}
