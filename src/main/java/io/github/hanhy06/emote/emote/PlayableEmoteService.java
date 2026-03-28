package io.github.hanhy06.emote.emote;

import io.github.hanhy06.emote.permission.EmotePermissionService;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

public class PlayableEmoteService {
	private final EmoteRegistry emoteRegistry;
	private final PlayPermissionChecker playPermissionChecker;

	public PlayableEmoteService(
		EmoteRegistry emoteRegistry,
		EmotePermissionService emotePermissionService
	) {
		this(
			emoteRegistry,
			(player, definition, animation) -> emotePermissionService.canPlay(player, definition.namespace(), animation.name())
		);
	}

	PlayableEmoteService(
		EmoteRegistry emoteRegistry,
		PlayPermissionChecker playPermissionChecker
	) {
		this.emoteRegistry = emoteRegistry;
		this.playPermissionChecker = playPermissionChecker;
	}

	public List<PlayableEmote> getPlayableEmotes(ServerPlayer player) {
		List<PlayableEmote> playableEmotes = new ArrayList<>();
		for (EmoteDefinition definition : this.emoteRegistry.getDefinitions()) {
			for (EmoteAnimation animation : definition.animations()) {
				if (!canPlay(player, definition, animation)) {
					continue;
				}

				playableEmotes.add(createPlayableEmote(definition, animation));
			}
		}

		playableEmotes.sort(Comparator.comparing(PlayableEmote::displayName).thenComparing(PlayableEmote::animationName));
		return List.copyOf(playableEmotes);
	}

	public List<String> getPlayablePlayNames(ServerPlayer player) {
		LinkedHashMap<String, String> playablePlayNameMap = new LinkedHashMap<>();
		for (EmoteDefinition definition : this.emoteRegistry.getDefinitions()) {
			if (!hasPlayableAnimation(player, definition)) {
				continue;
			}

			playablePlayNameMap.putIfAbsent(definition.commandName(), definition.commandName());
			playablePlayNameMap.putIfAbsent(definition.namespace(), definition.namespace());
		}

		return List.copyOf(playablePlayNameMap.values());
	}

	public List<String> getPlayableAnimationNamesForPlay(ServerPlayer player, String commandNameOrNamespace) {
		Optional<EmoteDefinition> definition = this.emoteRegistry.findDefinitionForPlay(commandNameOrNamespace);
		if (definition.isEmpty()) {
			return List.of();
		}

		EmoteDefinition definitionValue = definition.get();
		List<String> animationNames = new ArrayList<>();
		for (EmoteAnimation animation : definitionValue.animations()) {
			if (!canPlay(player, definitionValue, animation)) {
				continue;
			}

			animationNames.add(animation.name());
		}
		return List.copyOf(animationNames);
	}

	public PlayableEmoteSelectionResult findSelection(
		ServerPlayer player,
		String commandName,
		String animationName
	) {
		Optional<EmoteDefinition> definition = this.emoteRegistry.findDefinitionForPlay(commandName);
		if (definition.isEmpty()) {
			return PlayableEmoteSelectionResult.failure("Unknown: " + commandName);
		}

		EmoteDefinition definitionValue = definition.get();
		Optional<EmoteAnimation> animation = definitionValue.findAnimation(animationName);
		if (animation.isEmpty()) {
			return PlayableEmoteSelectionResult.failure("Unknown: " + definitionValue.commandName() + ":" + animationName);
		}

		return createSelectionResult(player, definitionValue, animation.get());
	}

	public PlayableEmoteSelectionResult findDefaultSelection(ServerPlayer player, String commandName) {
		Optional<EmoteDefinition> definition = this.emoteRegistry.findDefinitionForPlay(commandName);
		if (definition.isEmpty()) {
			return PlayableEmoteSelectionResult.failure("Unknown: " + commandName);
		}

		EmoteDefinition definitionValue = definition.get();
		Optional<EmoteAnimation> animation = definitionValue.findDefaultAnimation();
		if (animation.isEmpty()) {
			return PlayableEmoteSelectionResult.failure("No default: " + definitionValue.commandName());
		}

		return createSelectionResult(player, definitionValue, animation.get());
	}

	private boolean canPlay(ServerPlayer player, EmoteDefinition definition, EmoteAnimation animation) {
		return this.playPermissionChecker.canPlay(player, definition, animation);
	}

	private boolean hasPlayableAnimation(ServerPlayer player, EmoteDefinition definition) {
		for (EmoteAnimation animation : definition.animations()) {
			if (canPlay(player, definition, animation)) {
				return true;
			}
		}

		return false;
	}

	private PlayableEmoteSelectionResult createSelectionResult(
		ServerPlayer player,
		EmoteDefinition definition,
		EmoteAnimation animation
	) {
		if (!canPlay(player, definition, animation)) {
			return PlayableEmoteSelectionResult.failure("No emote permission.");
		}

		return PlayableEmoteSelectionResult.success(new PlayableEmoteSelection(definition, animation));
	}

	private PlayableEmote createPlayableEmote(EmoteDefinition definition, EmoteAnimation animation) {
		String animationName = animation.name();
		return new PlayableEmote(
			definition.commandName(),
			animationName,
			definition.isDefaultAnimation(animationName),
			definition.createDisplayName(animationName),
			definition.createDisplayDescription(animationName)
		);
	}

	@FunctionalInterface
	interface PlayPermissionChecker {
		boolean canPlay(ServerPlayer player, EmoteDefinition definition, EmoteAnimation animation);
	}
}
