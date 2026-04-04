package io.github.hanhy06.emote.network.service;

import io.github.hanhy06.emote.emote.EmoteAnimation;
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
	void playDefaultReturnsDisplayName() {
		PlayService service = new PlayService(
			createPlayableEmoteService(),
			(player, definition, animation) -> PlaybackStartResult.SUCCESS
		);

		PlayResult result = service.playDefault(null, "wave");

		assertTrue(result.isSuccess());
		assertEquals("Wave", result.displayName());
	}

	@Test
	void playSelectionReturnsPlaybackFailure() {
		PlayService service = new PlayService(
			createPlayableEmoteService(),
			(player, definition, animation) -> PlaybackStartResult.failure(" Datapack not loaded. ")
		);

		PlayResult result = service.playSelection(null, "wave", "default");

		assertFalse(result.isSuccess());
		assertEquals("Datapack not loaded.", result.errorMessage());
	}

	@Test
	void playSelectionUsesLoopDisplayName() {
		PlayService service = new PlayService(
			createLoopPlayableEmoteService(),
			(player, definition, animation) -> PlaybackStartResult.SUCCESS
		);

		PlayResult result = service.playSelection(null, "wave", "default_loop");

		assertTrue(result.isSuccess());
		assertEquals("Wave - default loop", result.displayName());
	}

	private PlayableEmoteService createPlayableEmoteService() {
		EmoteRegistry registry = new EmoteRegistry();
		registry.replaceDefinitions(List.of(new EmoteDefinition(
			"wave",
			"Wave",
			"Friendly wave",
			"wave",
			"default",
			Path.of("wave-pack"),
			1,
			List.of(new EmoteAnimation("default", 20)),
			List.of()
		)));
		return new PlayableEmoteService(registry, new PermissionService() {
			@Override
			public boolean canPlay(net.minecraft.server.level.ServerPlayer player, String namespace, String animationName) {
				return true;
			}
		});
	}

	private PlayableEmoteService createLoopPlayableEmoteService() {
		EmoteRegistry registry = new EmoteRegistry();
		registry.replaceDefinitions(List.of(new EmoteDefinition(
			"wave",
			"Wave",
			"Friendly wave",
			"wave",
			"default",
			"sync loop",
			Path.of("wave-pack"),
			1,
			List.of(
				new EmoteAnimation("default", 20),
				EmoteAnimation.createLoop("default", 20)
			),
			List.of()
		)));
		return new PlayableEmoteService(registry, new PermissionService() {
			@Override
			public boolean canPlay(net.minecraft.server.level.ServerPlayer player, String namespace, String animationName) {
				return true;
			}
		});
	}
}
