package io.github.hanhy06.emote.network;

import io.github.hanhy06.emote.emote.EmoteAnimation;
import io.github.hanhy06.emote.emote.EmoteDefinition;
import io.github.hanhy06.emote.emote.EmoteRegistry;
import io.github.hanhy06.emote.permission.EmotePermissionService;
import io.github.hanhy06.emote.playback.EmotePlaybackManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class EmoteWheelPlayService {
	private final EmoteRegistry emoteRegistry;
	private final EmotePermissionService emotePermissionService;
	private final EmotePlaybackManager emotePlaybackManager;

	public EmoteWheelPlayService(
		EmoteRegistry emoteRegistry,
		EmotePermissionService emotePermissionService,
		EmotePlaybackManager emotePlaybackManager
	) {
		this.emoteRegistry = emoteRegistry;
		this.emotePermissionService = emotePermissionService;
		this.emotePlaybackManager = emotePlaybackManager;
	}

	public void playPlayerSelection(ServerPlayer player, EmoteWheelPlayPayload payload) {
		EmoteDefinition definition = this.emoteRegistry.findDefinitionForPlay(payload.commandName()).orElse(null);
		if (definition == null) {
			player.sendSystemMessage(Component.literal("Unknown: " + payload.commandName()));
			return;
		}

		EmoteAnimation animation = definition.findAnimation(payload.animationName()).orElse(null);
		if (animation == null) {
			player.sendSystemMessage(Component.literal("Unknown: " + definition.commandName() + ":" + payload.animationName()));
			return;
		}

		if (!this.emotePermissionService.canPlay(player, definition.namespace(), animation.name())) {
			player.sendSystemMessage(Component.literal("No emote permission."));
			return;
		}

		this.emotePlaybackManager.startEmote(player, definition, animation)
			.ifPresent(errorMessage -> player.sendSystemMessage(Component.literal(errorMessage)));
	}
}
