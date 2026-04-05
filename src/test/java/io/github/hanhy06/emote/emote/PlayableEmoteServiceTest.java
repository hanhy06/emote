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
	void getPlayablePlayNamesKeepsOnlyVisibleDefinitions() {
		EmoteRegistry registry = new EmoteRegistry();
		registry.replaceDefinitions(List.of(
			createDefinition("wave_pack", "wave", "Wave", "Friendly wave", "default", "default"),
			createDefinition("bow_pack", "bow", "Bow", "Polite bow", "default", "default")
		));
		PlayableEmoteService service = new PlayableEmoteService(
			registry,
			(player, definition, animation) -> !definition.namespace().equals("bow_pack")
		);

		List<String> playNames = service.getPlayablePlayNames(null);

		assertEquals(List.of("wave", "wave_pack"), playNames);
	}

	@Test
	void getPlayableAnimationNamesForPlayFiltersBlockedAnimations() {
		EmoteRegistry registry = new EmoteRegistry();
		registry.replaceDefinitions(List.of(createDefinition("wave_pack", "wave", "Wave", "Friendly wave", "default", "default", "fast")));
		PlayableEmoteService service = new PlayableEmoteService(
			registry,
			(player, definition, animation) -> !animation.name().equals("fast")
		);

		List<String> animationNames = service.getPlayableAnimationNamesForPlay(null, "wave_pack");

		assertEquals(List.of("default"), animationNames);
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
	void findSelectionIncludesParsedOptions() {
		EmoteRegistry registry = new EmoteRegistry();
		registry.replaceDefinitions(List.of(new EmoteDefinition(
			"wave",
			"Wave",
			"Friendly wave",
			"wave",
			"default",
			"visible_player",
			Path.of("wave-pack"),
			1,
			List.of(new EmoteAnimation("default", 20)),
			List.of()
		)));
		PlayableEmoteService service = new PlayableEmoteService(registry, (player, definition, animation) -> true);

		PlayableEmoteSelectionResult result = service.findSelection(null, "wave", "default");

		assertTrue(result.isSuccess());
		assertTrue(result.selection().options().visiblePlayer());
		assertFalse(result.selection().options().loop());
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

	@Test
	void getPlayableEmotesPlacesLoopEntriesLastForEachEmote() {
		EmoteRegistry registry = new EmoteRegistry();
		registry.replaceDefinitions(List.of(new EmoteDefinition(
			"wave",
			"Wave",
			"Friendly wave",
			"wave",
			"default",
			"visible_player loop",
			Path.of("wave-pack"),
			1,
			List.of(
				new EmoteAnimation("default", 20),
				new EmoteAnimation("fast", 20),
				EmoteAnimation.createLoop("default", 20),
				EmoteAnimation.createLoop("fast", 20)
			),
			List.of()
		)));
		PlayableEmoteService service = new PlayableEmoteService(registry, (player, definition, animation) -> true);

		List<PlayableEmote> playableEmotes = service.getPlayableEmotes(null);

		assertEquals(
			List.of("default", "fast", "default_loop", "fast_loop"),
			playableEmotes.stream().map(PlayableEmote::animationName).toList()
		);
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
