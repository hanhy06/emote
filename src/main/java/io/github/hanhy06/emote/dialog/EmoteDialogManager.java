package io.github.hanhy06.emote.dialog;

import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.emote.EmoteRegistry;
import io.github.hanhy06.emote.emote.PlayableEmote;
import io.github.hanhy06.emote.emote.PlayableEmoteService;
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
import java.util.List;
import java.util.Optional;

public class EmoteDialogManager {
	private final EmoteRegistry emoteRegistry;
	private final PlayableEmoteService playableEmoteService;
	private final EmotePlaybackManager emotePlaybackManager;

	public EmoteDialogManager(
		EmoteRegistry emoteRegistry,
		PlayableEmoteService playableEmoteService,
		EmotePlaybackManager emotePlaybackManager
	) {
		this.emoteRegistry = emoteRegistry;
		this.playableEmoteService = playableEmoteService;
		this.emotePlaybackManager = emotePlaybackManager;
	}

	public void openDialog(ServerPlayer player, int pageNumber) {
		Dialog dialog = createRootDialog(player, pageNumber);
		player.openDialog(Holder.direct(dialog));
	}

	private Dialog createRootDialog(ServerPlayer player, int requestedPageNumber) {
		List<PlayableEmote> playableEmoteList = this.playableEmoteService.getPlayableEmotes(player);
		DialogPage dialogPage = createDialogPage(playableEmoteList.size(), requestedPageNumber);

		List<ActionButton> actionButtons = new ArrayList<>();
		for (PlayableEmote playableEmote : playableEmoteList.subList(dialogPage.startIndex(), dialogPage.endIndex())) {
			String command = "/" + playableEmote.createPlayCommand();
			actionButtons.add(createRunCommandButton(
				playableEmote.displayName(),
				playableEmote.description(),
				command
			));
		}

		if (this.emotePlaybackManager.findActiveEmote(player.getUUID()).isPresent()) {
			actionButtons.add(createRunCommandButton("Stop", "Stop", "/emote stop"));
		}

		appendPageButtons(actionButtons, dialogPage);

		if (actionButtons.isEmpty()) {
			actionButtons.add(createStaticButton("Close", "Close"));
		}

		List<DialogBody> dialogBody = List.of(new PlainMessage(
			Component.literal(createBodyText(dialogPage, player)),
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

	private void appendPageButtons(List<ActionButton> actionButtons, DialogPage dialogPage) {
		if (dialogPage.totalPageCount() <= 1) {
			return;
		}

		if ((actionButtons.size() & 1) != 0) {
			actionButtons.add(createStaticButton(" ", ""));
		}

		actionButtons.add(dialogPage.pageNumber() > 1
			? createRunCommandButton("Prev", "Open the previous emote page", "/emote menu " + (dialogPage.pageNumber() - 1))
			: createStaticButton("Prev", "No previous page"));
		actionButtons.add(dialogPage.pageNumber() < dialogPage.totalPageCount()
			? createRunCommandButton("Next", "Open the next emote page", "/emote menu " + (dialogPage.pageNumber() + 1))
			: createStaticButton("Next", "No next page"));
	}

	private String createBodyText(DialogPage dialogPage, ServerPlayer player) {
		if (this.emoteRegistry.size() == 0) {
			return "No emotes.";
		}

		Optional<ActiveEmote> activeEmote = this.emotePlaybackManager.findActiveEmote(player.getUUID());
		String activeEmoteText = activeEmote
			.map(value -> " Active: " + this.emoteRegistry.findDefinition(value.namespace())
				.map(definition -> definition.createDisplayName(value.animationName()))
				.orElse(value.namespace() + ":" + value.animationName()))
			.orElse("");

		if (dialogPage.playableEmoteCount() == 0) {
			return "No usable emotes." + activeEmoteText;
		}

		if (dialogPage.totalPageCount() == 1) {
			return "Emotes: " + dialogPage.playableEmoteCount() + "." + activeEmoteText;
		}

		return (dialogPage.startIndex() + 1) + "-" + dialogPage.endIndex() + "/" + dialogPage.playableEmoteCount()
			+ " | " + dialogPage.pageNumber() + "/" + dialogPage.totalPageCount() + "." + activeEmoteText;
	}

	private DialogPage createDialogPage(int playableEmoteCount, int requestedPageNumber) {
		int playButtonsPerPage = Math.max(1, ConfigManager.INSTANCE.getConfig().menu_page_size());
		int totalPageCount = Math.max(1, (int) Math.ceil((double) playableEmoteCount / playButtonsPerPage));
		int pageNumber = Math.max(1, Math.min(requestedPageNumber, totalPageCount));
		int startIndex = Math.min((pageNumber - 1) * playButtonsPerPage, playableEmoteCount);
		int endIndex = Math.min(startIndex + playButtonsPerPage, playableEmoteCount);
		return new DialogPage(playableEmoteCount, pageNumber, totalPageCount, startIndex, endIndex);
	}

	private record DialogPage(
		int playableEmoteCount,
		int pageNumber,
		int totalPageCount,
		int startIndex,
		int endIndex
	) {
	}
}
