package io.github.hanhy06.emote.dialog;

import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.emote.EmoteAnimation;
import io.github.hanhy06.emote.emote.EmoteDefinition;
import io.github.hanhy06.emote.emote.EmoteRegistry;
import io.github.hanhy06.emote.emote.PlayableEmote;
import io.github.hanhy06.emote.permission.EmotePermissionService;
import io.github.hanhy06.emote.playback.ActiveEmote;
import io.github.hanhy06.emote.playback.EmotePlaybackManager;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.action.Action;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class EmoteDialogManager {
	private final EmoteRegistry emoteRegistry;
	private final EmotePermissionService emotePermissionService;
	private final EmotePlaybackManager emotePlaybackManager;

	public EmoteDialogManager(
		EmoteRegistry emoteRegistry,
		EmotePermissionService emotePermissionService,
		EmotePlaybackManager emotePlaybackManager
	) {
		this.emoteRegistry = emoteRegistry;
		this.emotePermissionService = emotePermissionService;
		this.emotePlaybackManager = emotePlaybackManager;
	}

	public void openRootDialog(ServerPlayer player) {
		openDialog(player, 1);
	}

	public void openDialog(ServerPlayer player, int pageNumber) {
		Dialog dialog = createRootDialog(player, pageNumber);
		player.openDialog(Holder.direct(dialog));
	}

	private Dialog createRootDialog(ServerPlayer player, int requestedPageNumber) {
		List<PlayableEmote> playableEmoteList = getPlayableEmotes(player);
		int playButtonsPerPage = Math.max(1, ConfigManager.getConfig().menu_page_size());
		int totalPageCount = Math.max(1, (int) Math.ceil((double) playableEmoteList.size() / playButtonsPerPage));
		int pageNumber = Math.max(1, Math.min(requestedPageNumber, totalPageCount));
		int startIndex = Math.min((pageNumber - 1) * playButtonsPerPage, playableEmoteList.size());
		int endIndex = Math.min(startIndex + playButtonsPerPage, playableEmoteList.size());

		List<ActionButton> actionButtons = new ArrayList<>();
		for (PlayableEmote playableEmote : playableEmoteList.subList(startIndex, endIndex)) {
			String command = "/" + playableEmote.createPlayCommand();
			actionButtons.add(createRunCommandButton(
				playableEmote.displayName(),
				playableEmote.description(),
				command
			));
		}

		if (pageNumber > 1) {
			actionButtons.add(createRunCommandButton("Prev", "Open the previous emote page", "/emote menu " + (pageNumber - 1)));
		}

		if (pageNumber < totalPageCount) {
			actionButtons.add(createRunCommandButton("Next", "Open the next emote page", "/emote menu " + (pageNumber + 1)));
		}

		if (this.emotePermissionService.canStop(player) && this.emotePlaybackManager.findActiveEmote(player.getUUID()).isPresent()) {
			actionButtons.add(createRunCommandButton("Stop", "Stop", "/emote stop"));
		}

		if (actionButtons.isEmpty()) {
			actionButtons.add(createStaticButton("Close", "Close"));
		}

		List<DialogBody> dialogBody = List.of(new PlainMessage(
			Component.literal(createBodyText(playableEmoteList.size(), pageNumber, totalPageCount, startIndex, endIndex, player)),
			240
		));
		CommonDialogData commonDialogData = new CommonDialogData(
			Component.literal("Emote Menu"),
			Optional.empty(),
			true,
			false,
			DialogAction.CLOSE,
			dialogBody,
			List.of()
		);

		return new MultiActionDialog(commonDialogData, List.copyOf(actionButtons), Optional.empty(), 2);
	}

	private ActionButton createRunCommandButton(String label, String tooltip, String command) {
		CommonButtonData buttonData = new CommonButtonData(Component.literal(label), Optional.of(Component.literal(tooltip)), 150);
		Action action = new StaticAction(new ClickEvent.RunCommand(command));
		return new ActionButton(buttonData, Optional.of(action));
	}

	private ActionButton createStaticButton(String label, String tooltip) {
		CommonButtonData buttonData = new CommonButtonData(Component.literal(label), Optional.of(Component.literal(tooltip)), 150);
		return new ActionButton(buttonData, Optional.empty());
	}

	public List<PlayableEmote> getPlayableEmotes(ServerPlayer player) {
		List<PlayableEmote> playableEmotes = new ArrayList<>();
		for (EmoteDefinition definition : this.emoteRegistry.getDefinitions()) {
			String defaultAnimationName = definition.findDefaultAnimation()
				.map(EmoteAnimation::name)
				.orElse("");
			for (EmoteAnimation animation : definition.animations()) {
				String animationName = animation.name();
				if (!this.emotePermissionService.canPlay(player, definition.namespace(), animationName)) {
					continue;
				}

				boolean isDefaultAnimation = animationName.equals(defaultAnimationName);
				String displayName = definition.animations().size() <= 1 || isDefaultAnimation
					? definition.name()
					: definition.name() + " - " + animationName;
				String description = definition.animations().size() <= 1 || isDefaultAnimation
					? definition.description()
					: definition.description() + " (" + animationName + ")";

				playableEmotes.add(new PlayableEmote(
					definition.commandName(),
					animationName,
					isDefaultAnimation,
					displayName,
					description
				));
			}
		}

		playableEmotes.sort(Comparator.comparing(PlayableEmote::displayName).thenComparing(PlayableEmote::animationName));
		return List.copyOf(playableEmotes);
	}

	private String createBodyText(
		int playableEmoteCount,
		int pageNumber,
		int totalPageCount,
		int startIndex,
		int endIndex,
		ServerPlayer player
	) {
		if (this.emoteRegistry.size() == 0) {
			return "No emotes.";
		}

		Optional<ActiveEmote> activeEmote = this.emotePlaybackManager.findActiveEmote(player.getUUID());
		String activeEmoteText = activeEmote
			.map(value -> " Active: " + this.emoteRegistry.findDefinition(value.namespace())
				.map(definition -> definition.createDisplayName(value.animationName()))
				.orElse(value.namespace() + ":" + value.animationName()))
			.orElse("");

		if (playableEmoteCount == 0) {
			return "No usable emotes." + activeEmoteText;
		}

		if (totalPageCount == 1) {
			return "Emotes: " + playableEmoteCount + "." + activeEmoteText;
		}

		return (startIndex + 1) + "-" + endIndex + "/" + playableEmoteCount
			+ " | " + pageNumber + "/" + totalPageCount + "." + activeEmoteText;
	}
}
