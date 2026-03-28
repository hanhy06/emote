package io.github.hanhy06.emote.emote;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayableEmoteServiceTest {
	@Test
	void getPlayableEmotesSortsVisibleEntries() {
		EmoteRegistry registry = new EmoteRegistry();
		registry.replaceDefinitions(List.of(
			createDefinition("wave", "wave", "Wave", "Friendly wave", "default", "default", "fast"),
			createDefinition("bow", "bow", "Bow", "Polite bow", "default", "default")
		));
		PlayableEmoteService service = new PlayableEmoteService(
			registry,
			(player, definition, animation) -> !Set.of("wave:fast").contains(definition.namespace() + ":" + animation.name())
		);

		List<PlayableEmote> playableEmotes = service.getPlayableEmotes(null);

		assertEquals(List.of("Bow", "Wave"), playableEmotes.stream().map(PlayableEmote::displayName).toList());
	}

	@Test
	void findSelectionRejectsBlockedAnimation() {
		EmoteRegistry registry = new EmoteRegistry();
		registry.replaceDefinitions(List.of(createDefinition("wave", "wave", "Wave", "Friendly wave", "default", "default", "fast")));
		PlayableEmoteService service = new PlayableEmoteService(
			registry,
			(player, definition, animation) -> !Set.of("wave:fast").contains(definition.namespace() + ":" + animation.name())
		);

		PlayableEmoteSelectionResult blockedResult = service.findSelection(null, "wave", "fast");
		PlayableEmoteSelectionResult allowedResult = service.findSelection(null, "wave", "default");

		assertFalse(blockedResult.isSuccess());
		assertEquals("No emote permission.", blockedResult.errorMessage());
		assertTrue(allowedResult.isSuccess());
		assertNotNull(allowedResult.selection());
	}

	@Test
	void findSelectionReturnsUnknownAnimationMessage() {
		EmoteRegistry registry = new EmoteRegistry();
		registry.replaceDefinitions(List.of(createDefinition("wave", "wave", "Wave", "Friendly wave", "default", "default")));
		PlayableEmoteService service = new PlayableEmoteService(registry, (player, definition, animation) -> true);

		PlayableEmoteSelectionResult result = service.findSelection(null, "wave", "fast");

		assertFalse(result.isSuccess());
		assertEquals("Unknown: wave:fast", result.errorMessage());
	}

	@Test
	void findDefaultSelectionReturnsDefaultAnimation() {
		EmoteRegistry registry = new EmoteRegistry();
		registry.replaceDefinitions(List.of(createDefinition("wave", "wave", "Wave", "Friendly wave", "default", "default", "fast")));
		PlayableEmoteService service = new PlayableEmoteService(registry, (player, definition, animation) -> true);

		PlayableEmoteSelectionResult result = service.findDefaultSelection(null, "wave");

		assertTrue(result.isSuccess());
		assertEquals("default", result.selection().animation().name());
	}

	@Test
	void findDefaultSelectionReturnsNoDefaultWhenAnimationListIsEmpty() {
		EmoteRegistry registry = new EmoteRegistry();
		registry.replaceDefinitions(List.of(createDefinition("wave", "wave", "Wave", "Friendly wave", "default")));
		PlayableEmoteService service = new PlayableEmoteService(registry, (player, definition, animation) -> true);

		PlayableEmoteSelectionResult result = service.findDefaultSelection(null, "wave");

		assertFalse(result.isSuccess());
		assertEquals("No default: wave", result.errorMessage());
	}

	private EmoteDefinition createDefinition(
		String namespace,
		String commandName,
		String name,
		String description,
		String defaultAnimation,
		String... animationNames
	) {
		return new EmoteDefinition(
			namespace,
			name,
			description,
			commandName,
			defaultAnimation,
			Path.of(namespace + "-pack"),
			1,
			List.of(animationNames).stream().map(animationName -> new EmoteAnimation(animationName, 20)).toList(),
			List.of()
		);
	}
}
