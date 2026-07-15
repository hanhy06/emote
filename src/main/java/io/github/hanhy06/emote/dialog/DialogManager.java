package io.github.hanhy06.emote.dialog;

import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.emote.EmoteRegistry;
import io.github.hanhy06.emote.emote.PlayableEmote;
import io.github.hanhy06.emote.emote.PlayableEmoteService;
import io.github.hanhy06.emote.emote.RegisteredEmote;
import io.github.hanhy06.emote.playback.ActiveEmote;
import io.github.hanhy06.emote.playback.PlaybackManager;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.dialog.*;
import net.minecraft.server.dialog.action.Action;
import net.minecraft.server.dialog.action.CommandTemplate;
import net.minecraft.server.dialog.action.ParsedTemplate;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.dialog.input.TextInput;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class DialogManager {
    private static final int SMALL_BUTTON_WIDTH = 150;
    private static final int WIDE_BUTTON_WIDTH = 310;

    private final ConfigManager configManager;
    private final EmoteRegistry emoteRegistry;
    private final PlayableEmoteService playableEmoteService;
    private final PlaybackManager playbackManager;

    public DialogManager(
        ConfigManager configManager,
        EmoteRegistry emoteRegistry,
        PlayableEmoteService playableEmoteService,
        PlaybackManager playbackManager
    ) {
        this.configManager = configManager;
        this.emoteRegistry = emoteRegistry;
        this.playableEmoteService = playableEmoteService;
        this.playbackManager = playbackManager;
    }

    public void openDialog(ServerPlayer player, int pageNumber) {
        openDialog(player, pageNumber, "");
    }

    public void openDialog(ServerPlayer player, int pageNumber, String query) {
        String dialogQuery = query == null ? "" : query.trim();
        Dialog dialog = createRootDialog(player, pageNumber, dialogQuery);
        player.openDialog(Holder.direct(dialog));
    }

    public void openSearchDialog(ServerPlayer player) {
        List<Input> inputs = List.of(new Input("query", new TextInput(
            310,
            Component.literal("Search"),
            true,
            "",
            64,
            Optional.empty()
        )));
        CommonDialogData commonDialogData = new CommonDialogData(
            Component.literal("Search Emotes"),
            Optional.empty(),
            true,
            false,
            DialogAction.CLOSE,
            List.of(new PlainMessage(Component.literal("Search by name, command, or description."), 310)),
            inputs
        );
        ActionButton submitButton = createSearchSubmitButton();
        player.openDialog(Holder.direct(new MultiActionDialog(commonDialogData, List.of(submitButton), Optional.empty(), 1)));
    }

    private Dialog createRootDialog(ServerPlayer player, int requestedPageNumber, String query) {
        List<PlayableEmote> playableEmoteList = this.playableEmoteService.getPlayableEmotes(player, query);
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

        appendPageButtons(actionButtons, dialogPage, query);

        if (actionButtons.isEmpty()) {
            actionButtons.add(createStaticButton("Close", "Close"));
        }

        List<DialogBody> dialogBody = List.of(new PlainMessage(
            Component.literal(createBodyText(dialogPage, player, query)),
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

        if (query.isEmpty()) {
            actionButtons.add(createRunCommandButton(
                "Search",
                "Open emote search",
                "/emote search",
                searchButtonWidth(actionButtons.size())
            ));
        }
        return new MultiActionDialog(commonDialogData, List.copyOf(actionButtons), Optional.empty(), 2);
    }

    private ActionButton createRunCommandButton(String label, String tooltip, String command) {
        return createRunCommandButton(label, tooltip, command, SMALL_BUTTON_WIDTH);
    }

    private ActionButton createRunCommandButton(String label, String tooltip, String command, int width) {
        CommonButtonData buttonData = new CommonButtonData(Component.literal(label), Optional.of(Component.literal(tooltip)), width);
        Action action = new StaticAction(new ClickEvent.RunCommand(command));
        return new ActionButton(buttonData, Optional.of(action));
    }

    private ActionButton createSearchSubmitButton() {
        CommonButtonData buttonData = new CommonButtonData(
            Component.literal("Search"),
            Optional.of(Component.literal("Show matching emotes")),
            WIDE_BUTTON_WIDTH
        );
        ParsedTemplate parsedTemplate = ParsedTemplate.CODEC
            .parse(JsonOps.INSTANCE, new JsonPrimitive("/emote search $(query)"))
            .getOrThrow();
        return new ActionButton(buttonData, Optional.of(new CommandTemplate(parsedTemplate)));
    }

    private ActionButton createStaticButton(String label, String tooltip) {
        CommonButtonData buttonData = new CommonButtonData(
            Component.literal(label),
            Optional.of(Component.literal(tooltip)),
            SMALL_BUTTON_WIDTH
        );
        return new ActionButton(buttonData, Optional.empty());
    }

    static int searchButtonWidth(int existingButtonCount) {
        return (existingButtonCount & 1) == 0 ? WIDE_BUTTON_WIDTH : SMALL_BUTTON_WIDTH;
    }

    private void appendPageButtons(List<ActionButton> actionButtons, DialogPage dialogPage, String query) {
        if (dialogPage.totalPageCount() <= 1) {
            return;
        }

        if ((actionButtons.size() & 1) != 0) {
            actionButtons.add(createStaticButton(" ", ""));
        }

        actionButtons.add(dialogPage.pageNumber() > 1
            ? createRunCommandButton("Prev", "Open the previous emote page", createPageCommand(dialogPage.pageNumber() - 1, query))
            : createStaticButton("Prev", "No previous page"));
        actionButtons.add(dialogPage.pageNumber() < dialogPage.totalPageCount()
            ? createRunCommandButton("Next", "Open the next emote page", createPageCommand(dialogPage.pageNumber() + 1, query))
            : createStaticButton("Next", "No next page"));
    }

    static String createPageCommand(int pageNumber, String query) {
        return query.isEmpty()
            ? "/emote " + pageNumber
            : "/emote search " + com.mojang.brigadier.arguments.StringArgumentType.escapeIfRequired(query) + " " + pageNumber;
    }

    private String createBodyText(DialogPage dialogPage, ServerPlayer player, String query) {
        if (this.emoteRegistry.size() == 0) {
            return "No emotes.";
        }

        ActiveEmote activeEmote = this.playbackManager.findActiveEmote(player.getUUID());
        String activeEmoteText = activeEmote == null
            ? ""
            : createActiveEmoteText(activeEmote);

        if (dialogPage.playableEmoteCount() == 0) {
            return (query.isEmpty() ? "No usable emotes." : "No matching emotes.") + activeEmoteText;
        }

        if (dialogPage.totalPageCount() == 1) {
            return "Emotes: " + dialogPage.playableEmoteCount() + "." + activeEmoteText;
        }

        return (dialogPage.startIndex() + 1) + "-" + dialogPage.endIndex() + "/" + dialogPage.playableEmoteCount()
            + " | " + dialogPage.pageNumber() + "/" + dialogPage.totalPageCount() + "." + activeEmoteText;
    }

    private DialogPage createDialogPage(int playableEmoteCount, int requestedPageNumber) {
        int playButtonsPerPage = this.configManager.getConfig().menuPageSize();
        int totalPageCount = Math.max(1, (int) Math.ceil((double) playableEmoteCount / playButtonsPerPage));
        int pageNumber = Math.clamp(requestedPageNumber, 1, totalPageCount);
        int startIndex = Math.min((pageNumber - 1) * playButtonsPerPage, playableEmoteCount);
        int endIndex = Math.min(startIndex + playButtonsPerPage, playableEmoteCount);
        return new DialogPage(playableEmoteCount, pageNumber, totalPageCount, startIndex, endIndex);
    }

    private String createActiveEmoteText(ActiveEmote activeEmote) {
        RegisteredEmote emote = this.emoteRegistry.find(activeEmote.id());
        String displayName = emote == null
            ? activeEmote.id()
            : emote.name();
        return " Active: " + displayName;
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
