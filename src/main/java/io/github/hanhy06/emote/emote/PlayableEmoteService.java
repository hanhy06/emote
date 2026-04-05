package io.github.hanhy06.emote.emote;

import io.github.hanhy06.emote.permission.PermissionService;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

public class PlayableEmoteService {
	private final EmoteRegistry emoteRegistry;
	private final PlayPermissionChecker playPermissionChecker;

	public PlayableEmoteService(
		EmoteRegistry emoteRegistry,
		PermissionService permissionService
	) {
		this(
			emoteRegistry,
			(player, definition, animation) -> permissionService.canPlay(player, definition.namespace(), animation.datapackAnimationName())
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
		List<PlayableEmoteEntry> playableEmoteEntries = new ArrayList<>();
		for (EmoteDefinition definition : this.emoteRegistry.getDefinitions()) {
			for (EmoteAnimation animation : definition.animations()) {
				if (!canPlay(player, definition, animation)) {
					continue;
				}

				playableEmoteEntries.add(new PlayableEmoteEntry(
					createPlayableEmote(definition, animation),
					definition.name(),
					definition.commandName(),
					definition.isDefaultAnimation(animation.datapackAnimationName()),
					animation.displayName(),
					animation.loop()
				));
			}
		}

		playableEmoteEntries.sort(Comparator
			.comparing(PlayableEmoteEntry::definitionName)
			.thenComparing(PlayableEmoteEntry::commandName)
			.thenComparing(PlayableEmoteEntry::loop)
			.thenComparing(entry -> !entry.defaultAnimation())
			.thenComparing(PlayableEmoteEntry::animationDisplayName)
		);
		return playableEmoteEntries.stream()
			.map(PlayableEmoteEntry::playableEmote)
			.toList();
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
		EmoteDefinition definition = this.emoteRegistry.findDefinitionForPlay(commandNameOrNamespace);
		if (definition == null) {
			return List.of();
		}

		List<String> animationNames = new ArrayList<>();
		for (EmoteAnimation animation : definition.animations()) {
			if (!canPlay(player, definition, animation)) {
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
		EmoteDefinition definition = this.emoteRegistry.findDefinitionForPlay(commandName);
		if (definition == null) {
			return PlayableEmoteSelectionResult.failure("Unknown: " + commandName);
		}

		EmoteAnimation animation = definition.findAnimation(animationName);
		if (animation == null) {
			return PlayableEmoteSelectionResult.failure("Unknown: " + definition.commandName() + ":" + animationName);
		}

		return createSelectionResult(player, definition, animation);
	}

	public PlayableEmoteSelectionResult findDefaultSelection(ServerPlayer player, String commandName) {
		EmoteDefinition definition = this.emoteRegistry.findDefinitionForPlay(commandName);
		if (definition == null) {
			return PlayableEmoteSelectionResult.failure("Unknown: " + commandName);
		}

		EmoteAnimation animation = definition.findDefaultAnimation();
		if (animation == null) {
			return PlayableEmoteSelectionResult.failure("No default: " + definition.commandName());
		}

		return createSelectionResult(player, definition, animation);
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

		return PlayableEmoteSelectionResult.success(new PlayableEmoteSelection(
			definition,
			animation,
			definition.parsedOptions()
		));
	}

	private PlayableEmote createPlayableEmote(EmoteDefinition definition, EmoteAnimation animation) {
		String animationName = animation.name();
		String animationDisplayName = animation.displayName();
		return new PlayableEmote(
			definition.commandName(),
			animationName,
			definition.isDefaultAnimation(animationName),
			definition.createDisplayName(animationDisplayName),
			definition.createDisplayDescription(animationDisplayName)
		);
	}

	private record PlayableEmoteEntry(
		PlayableEmote playableEmote,
		String definitionName,
		String commandName,
		boolean defaultAnimation,
		String animationDisplayName,
		boolean loop
	) {
	}

	@FunctionalInterface
	interface PlayPermissionChecker {
		boolean canPlay(ServerPlayer player, EmoteDefinition definition, EmoteAnimation animation);
	}
}
