package io.github.hanhy06.emote.emote;

import io.github.hanhy06.emote.permission.EmotePermissionService;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

	public PlayableEmoteSelectionResult findSelection(
		ServerPlayer player,
		String commandName,
		String animationName
	) {
		EmoteDefinition definition = this.emoteRegistry.findDefinitionForPlay(commandName).orElse(null);
		if (definition == null) {
			return PlayableEmoteSelectionResult.failure("Unknown: " + commandName);
		}

		EmoteAnimation animation = definition.findAnimation(animationName).orElse(null);
		if (animation == null) {
			return PlayableEmoteSelectionResult.failure("Unknown: " + definition.commandName() + ":" + animationName);
		}

		return createSelectionResult(player, definition, animation);
	}

	public PlayableEmoteSelectionResult findDefaultSelection(ServerPlayer player, String commandName) {
		EmoteDefinition definition = this.emoteRegistry.findDefinitionForPlay(commandName).orElse(null);
		if (definition == null) {
			return PlayableEmoteSelectionResult.failure("Unknown: " + commandName);
		}

		EmoteAnimation animation = definition.findDefaultAnimation().orElse(null);
		if (animation == null) {
			return PlayableEmoteSelectionResult.failure("No default: " + definition.commandName());
		}

		return createSelectionResult(player, definition, animation);
	}

	private boolean canPlay(ServerPlayer player, EmoteDefinition definition, EmoteAnimation animation) {
		return this.playPermissionChecker.canPlay(player, definition, animation);
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
