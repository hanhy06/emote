package io.github.hanhy06.emote.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.hanhy06.emote.api.PlayResult;
import io.github.hanhy06.emote.api.PlaySource;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.dialog.DialogManager;
import io.github.hanhy06.emote.emote.EmoteRegistry;
import io.github.hanhy06.emote.emote.PlayService;
import io.github.hanhy06.emote.emote.PlayableEmoteService;
import io.github.hanhy06.emote.permission.PermissionService;
import io.github.hanhy06.emote.playback.ActiveEmote;
import io.github.hanhy06.emote.playback.PlaybackManager;
import io.github.hanhy06.emote.server.EmoteReloadService;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.List;

public final class RootCommand {
    private final PlaybackManager playbackManager;
    private final DialogManager dialogManager;
    private final PlayableEmoteService playableEmoteService;
    private final PlayService playService;
    private final EmoteAdminCommands adminCommands;

    public RootCommand(
        EmoteRegistry emoteRegistry,
        PlaybackManager playbackManager,
        DialogManager dialogManager,
        PlayableEmoteService playableEmoteService,
        PlayService playService,
        PermissionService permissionService,
        EmoteReloadService reloadService,
        ConfigManager configManager
    ) {
        this.playbackManager = playbackManager;
        this.dialogManager = dialogManager;
        this.playableEmoteService = playableEmoteService;
        this.playService = playService;
        this.adminCommands = new EmoteAdminCommands(
            emoteRegistry,
            playbackManager,
            permissionService,
            reloadService,
            configManager
        );
    }

    public void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, ignoredRegistryAccess, ignoredEnvironment) ->
            dispatcher.register(createRootCommand())
        );
    }

    private LiteralArgumentBuilder<CommandSourceStack> createRootCommand() {
        return Commands.literal("emote")
            .executes(context -> openMenu(context.getSource()))
            .then(Commands.argument("page", IntegerArgumentType.integer(1))
                .executes(context -> openMenu(
                    context.getSource(),
                    IntegerArgumentType.getInteger(context, "page")
                )))
            .then(createSearchCommand())
            .then(this.adminCommands.createListCommand())
            .then(this.adminCommands.createReloadCommand())
            .then(createPlayCommand())
            .then(createStopCommand())
            .then(this.adminCommands.createStopAllCommand())
            .then(this.adminCommands.createLoadTestCommand())
            .then(this.adminCommands.createEnableCommand())
            .then(this.adminCommands.createDisableCommand());
    }

    private LiteralArgumentBuilder<CommandSourceStack> createSearchCommand() {
        return Commands.literal("search")
            .executes(context -> openSearch(context.getSource()))
            .then(Commands.argument("query", StringArgumentType.string())
                .executes(context -> openMenu(
                    context.getSource(),
                    1,
                    StringArgumentType.getString(context, "query")
                ))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                    .executes(context -> openMenu(
                        context.getSource(),
                        IntegerArgumentType.getInteger(context, "page"),
                        StringArgumentType.getString(context, "query")
                    ))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> createPlayCommand() {
        return Commands.literal("play")
            .then(Commands.argument("id", IdentifierArgument.id())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                    getSuggestedPlayNames(context.getSource()),
                    builder
                ))
                .executes(this::playEmote));
    }

    private LiteralArgumentBuilder<CommandSourceStack> createStopCommand() {
        return Commands.literal("stop")
            .executes(context -> stopEmote(context.getSource()));
    }

    private List<String> getSuggestedPlayNames(CommandSourceStack source) {
        ServerPlayer player = findPlayer(source);
        return player == null
            ? this.playableEmoteService.getPlayIds()
            : this.playableEmoteService.getPlayablePlayIds(player);
    }

    private int openMenu(CommandSourceStack source) throws CommandSyntaxException {
        return openMenu(source, 1);
    }

    private int openMenu(
        CommandSourceStack source,
        int pageNumber
    ) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        this.dialogManager.openDialog(player, pageNumber);
        return 1;
    }

    private int openSearch(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        this.dialogManager.openSearchDialog(player);
        return 1;
    }

    private int openMenu(
        CommandSourceStack source,
        int pageNumber,
        String query
    ) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        this.dialogManager.openDialog(player, pageNumber, query);
        return 1;
    }

    private static int applyPlayResult(CommandSourceStack source, PlayResult playResult) {
        if (!playResult.isSuccess()) {
            source.sendFailure(playResult.errorMessage());
            return 0;
        }

        return 1;
    }

    private int playEmote(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        return applyPlayResult(
            source,
            this.playService.play(player, IdentifierArgument.getId(context, "id").toString(), PlaySource.COMMAND)
        );
    }

    private int stopEmote(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ActiveEmote activeEmote = this.playbackManager.stop(player);
        if (activeEmote == null) {
            source.sendFailure(Component.literal("No active emote."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Stop: " + activeEmote.id()), false);
        return 1;
    }

    private static ServerPlayer findPlayer(CommandSourceStack source) {
        Entity entity = source.getEntity();
        return entity instanceof ServerPlayer player ? player : null;
    }
}
