package io.github.hanhy06.emote.network;

import io.github.hanhy06.emote.dialog.EmoteDialogManager;
import io.github.hanhy06.emote.emote.EmoteAnimation;
import io.github.hanhy06.emote.emote.EmoteDefinition;
import io.github.hanhy06.emote.emote.EmoteRegistry;
import io.github.hanhy06.emote.emote.PlayableEmote;
import io.github.hanhy06.emote.playback.EmotePlaybackManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

public class EmoteWheelPlayService {
	private final EmoteRegistry emoteRegistry;
	private final EmoteDialogManager emoteDialogManager;
	private final EmotePlaybackManager emotePlaybackManager;

	public EmoteWheelPlayService(
		EmoteRegistry emoteRegistry,
		EmoteDialogManager emoteDialogManager,
		EmotePlaybackManager emotePlaybackManager
	) {
		this.emoteRegistry = emoteRegistry;
		this.emoteDialogManager = emoteDialogManager;
		this.emotePlaybackManager = emotePlaybackManager;
	}

	public void playPlayerSelection(ServerPlayer player, EmoteWheelPlayPayload payload) {
		Optional<PlayableEmote> playableEmote = this.emoteDialogManager.getPlayableEmotes(player).stream()
			.filter(value -> value.commandName().equals(payload.commandName()))
			.filter(value -> value.animationName().equals(payload.animationName()))
			.findFirst();
		if (playableEmote.isEmpty()) {
			player.sendSystemMessage(Component.literal("No emote permission."));
			return;
		}

		Optional<EmoteDefinition> definition = this.emoteRegistry.findDefinitionForPlay(playableEmote.get().commandName());
		if (definition.isEmpty()) {
			player.sendSystemMessage(Component.literal("Unknown: " + payload.commandName()));
			return;
		}

		Optional<EmoteAnimation> animation = definition.get().findAnimation(playableEmote.get().animationName());
		if (animation.isEmpty()) {
			player.sendSystemMessage(Component.literal("Unknown: " + definition.get().commandName() + ":" + playableEmote.get().animationName()));
			return;
		}

		this.emotePlaybackManager.startEmote(player, definition.get(), animation.get())
			.ifPresent(errorMessage -> player.sendSystemMessage(Component.literal(errorMessage)));
	}
}
