package io.github.hanhy06.emote.dialog;

import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.emote.EmoteDefinition;
import io.github.hanhy06.emote.emote.EmoteRegistry;
import io.github.hanhy06.emote.emote.PlayableEmote;
import io.github.hanhy06.emote.emote.PlayableEmoteService;
import io.github.hanhy06.emote.playback.PlaybackManager;
import io.github.hanhy06.emote.playback.data.ActiveEmote;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.action.Action;
import net.minecraft.server.dialog.action.CommandTemplate;
import net.minecraft.server.dialog.action.ParsedTemplate;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.dialog.input.TextInput;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class DialogManager {
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
        Dialog dialog = createRootDialog(player, pageNumber, normalizeQuery(query));
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
        ActionButton submitButton = createTemplateButton(
            "Search",
            "Show matching emotes",
            "/emote search $(query)",
            310
        );
        player.openDialog(Holder.direct(new MultiActionDialog(commonDialogData, List.of(submitButton), Optional.empty(), 1)));
    }

    private Dialog createRootDialog(ServerPlayer player, int requestedPageNumber, String query) {
        List<PlayableEmote> playableEmoteList = filterPlayableEmotes(this.playableEmoteService.getPlayableEmotes(player), query);
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
                310
            ));
        }
        return new MultiActionDialog(commonDialogData, List.copyOf(actionButtons), Optional.empty(), 2);
    }

    private ActionButton createRunCommandButton(String label, String tooltip, String command) {
        return createRunCommandButton(label, tooltip, command, 150);
    }

    private ActionButton createRunCommandButton(String label, String tooltip, String command, int width) {
        CommonButtonData buttonData = new CommonButtonData(Component.literal(label), Optional.of(Component.literal(tooltip)), width);
        Action action = new StaticAction(new ClickEvent.RunCommand(command));
        return new ActionButton(buttonData, Optional.of(action));
    }

    private ActionButton createTemplateButton(String label, String tooltip, String commandTemplate, int width) {
        CommonButtonData buttonData = new CommonButtonData(Component.literal(label), Optional.of(Component.literal(tooltip)), width);
        ParsedTemplate parsedTemplate = ParsedTemplate.CODEC
            .parse(JsonOps.INSTANCE, new JsonPrimitive(commandTemplate))
            .getOrThrow();
        return new ActionButton(buttonData, Optional.of(new CommandTemplate(parsedTemplate)));
    }

    private ActionButton createStaticButton(String label, String tooltip) {
        CommonButtonData buttonData = new CommonButtonData(Component.literal(label), Optional.of(Component.literal(tooltip)), 150);
        return new ActionButton(buttonData, Optional.empty());
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

    private String createPageCommand(int pageNumber, String query) {
        return query.isEmpty()
            ? "/emote menu " + pageNumber
            : "/emote search " + com.mojang.brigadier.arguments.StringArgumentType.escapeIfRequired(query) + " " + pageNumber;
    }

    static List<PlayableEmote> filterPlayableEmotes(List<PlayableEmote> emotes, String query) {
        String normalizedQuery = normalizeQuery(query);
        if (normalizedQuery.isEmpty()) {
            return List.copyOf(emotes);
        }

        return emotes.stream()
            .filter(emote -> searchRank(emote, normalizedQuery) < Integer.MAX_VALUE)
            .sorted(Comparator.comparingInt(emote -> searchRank(emote, normalizedQuery)))
            .toList();
    }

    private static int searchRank(PlayableEmote emote, String query) {
        String displayName = emote.displayName().toLowerCase(Locale.ROOT);
        String commandName = emote.commandName().toLowerCase(Locale.ROOT);
        String description = emote.description().toLowerCase(Locale.ROOT);
        if (displayName.equals(query)) return 0;
        if (displayName.startsWith(query)) return 1;
        if (commandName.startsWith(query)) return 2;
        if (displayName.contains(query) || commandName.contains(query) || description.contains(query)) return 3;
        return Integer.MAX_VALUE;
    }

    private static String normalizeQuery(String query) {
        return query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
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
        int playButtonsPerPage = Math.max(1, this.configManager.getConfig().menu_page_size());
        int totalPageCount = Math.max(1, (int) Math.ceil((double) playableEmoteCount / playButtonsPerPage));
        int pageNumber = Math.clamp(requestedPageNumber, 1, totalPageCount);
        int startIndex = Math.min((pageNumber - 1) * playButtonsPerPage, playableEmoteCount);
        int endIndex = Math.min(startIndex + playButtonsPerPage, playableEmoteCount);
        return new DialogPage(playableEmoteCount, pageNumber, totalPageCount, startIndex, endIndex);
    }

    private String createActiveEmoteText(ActiveEmote activeEmote) {
        EmoteDefinition definition = this.emoteRegistry.findDefinition(activeEmote.namespace());
        String displayName = definition == null
            ? activeEmote.namespace()
            : definition.name();
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
