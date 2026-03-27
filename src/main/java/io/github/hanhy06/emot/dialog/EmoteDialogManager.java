package io.github.hanhy06.emot.dialog;

import io.github.hanhy06.emot.emote.EmoteAnimation;
import io.github.hanhy06.emot.emote.EmoteDefinition;
import io.github.hanhy06.emot.emote.EmoteRegistry;
import io.github.hanhy06.emot.permission.EmotePermissionService;
import io.github.hanhy06.emot.playback.ActiveEmote;
import io.github.hanhy06.emot.playback.EmotePlaybackManager;
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
	private static final int PLAY_BUTTONS_PER_PAGE = 6;
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
		List<PlayableEmote> playableEmoteList = getPlayableEmoteList(player);
		int totalPageCount = Math.max(1, (int) Math.ceil((double) playableEmoteList.size() / PLAY_BUTTONS_PER_PAGE));
		int pageNumber = Math.max(1, Math.min(requestedPageNumber, totalPageCount));
		int startIndex = Math.min((pageNumber - 1) * PLAY_BUTTONS_PER_PAGE, playableEmoteList.size());
		int endIndex = Math.min(startIndex + PLAY_BUTTONS_PER_PAGE, playableEmoteList.size());

		List<ActionButton> actionButtons = new ArrayList<>();
		for (PlayableEmote playableEmote : playableEmoteList.subList(startIndex, endIndex)) {
			actionButtons.add(createRunCommandButton(
				playableEmote.namespace() + ":" + playableEmote.animationName(),
				"Run " + playableEmote.namespace() + ":" + playableEmote.animationName(),
				"/emote play " + playableEmote.namespace() + " " + playableEmote.animationName()
			));
		}

		if (pageNumber > 1) {
			actionButtons.add(createRunCommandButton("Prev", "Open the previous emote page", "/emote menu " + (pageNumber - 1)));
		}

		if (pageNumber < totalPageCount) {
			actionButtons.add(createRunCommandButton("Next", "Open the next emote page", "/emote menu " + (pageNumber + 1)));
		}

		if (this.emotePermissionService.canStop(player) && this.emotePlaybackManager.findActiveEmote(player.getUUID()).isPresent()) {
			actionButtons.add(createRunCommandButton("Stop", "Stop the current emote session", "/emote stop"));
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

	private List<PlayableEmote> getPlayableEmoteList(ServerPlayer player) {
		return this.emoteRegistry.getDefinitions().stream()
			.flatMap(definition -> definition.animations().stream().map(animation -> new PlayableEmote(definition.namespace(), animation.name())))
			.filter(playableEmote -> this.emotePermissionService.canPlay(player, playableEmote.namespace(), playableEmote.animationName()))
			.sorted(Comparator.comparing(PlayableEmote::namespace).thenComparing(PlayableEmote::animationName))
			.toList();
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
			return "No BD Engine emotes were found in the world datapacks folder.";
		}

		Optional<ActiveEmote> activeEmote = this.emotePlaybackManager.findActiveEmote(player.getUUID());
		String activeEmoteText = activeEmote
			.map(value -> " Active: " + value.namespace() + ":" + value.animationName() + ".")
			.orElse("");

		if (playableEmoteCount == 0) {
			return "No emotes are available for your current permissions." + activeEmoteText;
		}

		if (totalPageCount == 1) {
			return "Showing " + playableEmoteCount + " emotes you can run right now." + activeEmoteText;
		}

		return "Showing emotes " + (startIndex + 1) + "-" + endIndex + " of " + playableEmoteCount
			+ ". Page " + pageNumber + "/" + totalPageCount + "." + activeEmoteText;
	}

	private record PlayableEmote(String namespace, String animationName) {
	}
}
