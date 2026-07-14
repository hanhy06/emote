package io.github.hanhy06.emote.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.dialog.DialogManager;
import io.github.hanhy06.emote.emote.EmoteDefinition;
import io.github.hanhy06.emote.emote.EmoteRegistry;
import io.github.hanhy06.emote.emote.PlayableEmoteService;
import io.github.hanhy06.emote.network.service.PlayResult;
import io.github.hanhy06.emote.network.service.PlayService;
import io.github.hanhy06.emote.permission.PermissionService;
import io.github.hanhy06.emote.playback.PlaybackManager;
import io.github.hanhy06.emote.playback.data.ActiveEmote;
import io.github.hanhy06.emote.server.EmoteReloadResult;
import io.github.hanhy06.emote.server.EmoteReloadService;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.List;

public final class RootCommand {
    private RootCommand() {
    }

    public static void register(
        EmoteRegistry emoteRegistry,
        PlaybackManager playbackManager,
        DialogManager dialogManager,
        PlayableEmoteService playableEmoteService,
        PlayService playService,
        PermissionService permissionService,
        EmoteReloadService reloadService
    ) {
        CommandRegistrationCallback.EVENT.register((dispatcher, ignoredRegistryAccess, ignoredEnvironment) ->
            dispatcher.register(createRootCommand(
                emoteRegistry,
                playbackManager,
                dialogManager,
                playableEmoteService,
                playService,
                permissionService,
                reloadService
            ))
        );
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createRootCommand(
        EmoteRegistry emoteRegistry,
        PlaybackManager playbackManager,
        DialogManager dialogManager,
        PlayableEmoteService playableEmoteService,
        PlayService playService,
        PermissionService permissionService,
        EmoteReloadService reloadService
    ) {
        return Commands.literal("emote")
            .executes(context -> openMenu(context.getSource(), dialogManager, permissionService))
            .then(createMenuCommand(dialogManager, permissionService))
            .then(createSearchCommand(dialogManager, permissionService))
            .then(createListCommand(emoteRegistry, permissionService))
            .then(createReloadCommand(reloadService, permissionService))
            .then(createPlayCommand(emoteRegistry, playableEmoteService, playService, permissionService))
            .then(createStopCommand(playbackManager, permissionService));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createMenuCommand(
        DialogManager dialogManager,
        PermissionService permissionService
    ) {
        return Commands.literal("menu")
            .executes(context -> openMenu(context.getSource(), dialogManager, permissionService))
            .then(Commands.argument("page", IntegerArgumentType.integer(1))
                .executes(context -> openMenu(
                    context.getSource(),
                    dialogManager,
                    permissionService,
                    IntegerArgumentType.getInteger(context, "page")
                )));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createSearchCommand(
        DialogManager dialogManager,
        PermissionService permissionService
    ) {
        return Commands.literal("search")
            .executes(context -> openSearch(context.getSource(), dialogManager, permissionService))
            .then(Commands.argument("query", StringArgumentType.string())
                .executes(context -> openMenu(
                    context.getSource(),
                    dialogManager,
                    permissionService,
                    1,
                    StringArgumentType.getString(context, "query")
                ))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                    .executes(context -> openMenu(
                        context.getSource(),
                        dialogManager,
                        permissionService,
                        IntegerArgumentType.getInteger(context, "page"),
                        StringArgumentType.getString(context, "query")
                    ))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createListCommand(
        EmoteRegistry emoteRegistry,
        PermissionService permissionService
    ) {
        return Commands.literal("list")
            .executes(context -> listEmotes(context.getSource(), emoteRegistry, permissionService));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createReloadCommand(
        EmoteReloadService reloadService,
        PermissionService permissionService
    ) {
        return Commands.literal("reload")
            .requires(permissionService.requireReload())
            .executes(context -> reloadEmotes(context.getSource(), reloadService));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createPlayCommand(
        EmoteRegistry emoteRegistry,
        PlayableEmoteService playableEmoteService,
        PlayService playService,
        PermissionService permissionService
    ) {
        return Commands.literal("play")
            .then(Commands.argument("emote", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                    getSuggestedPlayNames(context.getSource(), emoteRegistry, playableEmoteService),
                    builder
                ))
                .executes(context -> playEmote(context, playService)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createStopCommand(
        PlaybackManager playbackManager,
        PermissionService permissionService
    ) {
        return Commands.literal("stop")
            .executes(context -> stopEmote(context.getSource(), playbackManager, permissionService));
    }

    private static List<String> getSuggestedPlayNames(
        CommandSourceStack source,
        EmoteRegistry emoteRegistry,
        PlayableEmoteService playableEmoteService
    ) {
        ServerPlayer player = findPlayer(source);
        return player == null
            ? emoteRegistry.getPlayNames()
            : playableEmoteService.getPlayablePlayNames(player);
    }

    private static int openMenu(
        CommandSourceStack source,
        DialogManager dialogManager,
        PermissionService permissionService
    ) throws CommandSyntaxException {
        return openMenu(source, dialogManager, permissionService, 1);
    }

    private static int openMenu(
        CommandSourceStack source,
        DialogManager dialogManager,
        PermissionService permissionService,
        int pageNumber
    ) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!permissionService.canOpenDialog(player)) {
            source.sendFailure(Component.literal("No menu permission."));
            return 0;
        }

        dialogManager.openDialog(player, pageNumber);
        return 1;
    }

    private static int openSearch(
        CommandSourceStack source,
        DialogManager dialogManager,
        PermissionService permissionService
    ) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!permissionService.canOpenDialog(player)) {
            source.sendFailure(Component.literal("No menu permission."));
            return 0;
        }

        dialogManager.openSearchDialog(player);
        return 1;
    }

    private static int openMenu(
        CommandSourceStack source,
        DialogManager dialogManager,
        PermissionService permissionService,
        int pageNumber,
        String query
    ) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!permissionService.canOpenDialog(player)) {
            source.sendFailure(Component.literal("No menu permission."));
            return 0;
        }

        dialogManager.openDialog(player, pageNumber, query);
        return 1;
    }

    private static int listEmotes(
        CommandSourceStack source,
        EmoteRegistry emoteRegistry,
        PermissionService permissionService
    ) {
        if (!permissionService.canList(source)) {
            source.sendFailure(Component.literal("No list permission."));
            return 0;
        }

        List<EmoteDefinition> definitions = emoteRegistry.getDefinitions();
        if (definitions.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No emotes."), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Emotes: " + definitions.size()), false);

        for (EmoteDefinition definition : definitions) {
            source.sendSystemMessage(Component.literal(
                "- " + definition.namespace()
                    + " cmd=" + definition.commandName()
                    + " name=" + definition.name()
                    + " parts=" + definition.partCount()
                    + " entrypoint=" + definition.entrypoint()
            ));
        }

        return definitions.size();
    }

    private static int reloadEmotes(
        CommandSourceStack source,
        EmoteReloadService reloadService
    ) {
        if (Emote.SERVER == null) {
            source.sendFailure(Component.literal("Server unavailable."));
            return 0;
        }

        EmoteReloadResult result = reloadService.reloadFromCommand();
        source.sendSuccess(
            () -> Component.literal(
                "Reloading: cfg=" + result.configLoaded()
                    + ", packs=" + result.packConfigLoaded()
                    + ", emotes=" + result.emoteCount()
                    + (result.resourceReload() ? " (resource reload)" : "")
            ),
            true
        );
        return result.emoteCount();
    }

    private static int applyPlayResult(CommandSourceStack source, PlayResult playResult) {
        if (!playResult.isSuccess()) {
            source.sendFailure(Component.literal(playResult.errorMessage()));
            return 0;
        }

        return 1;
    }

    private static int playEmote(
        CommandContext<CommandSourceStack> context,
        PlayService playService
    ) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        return applyPlayResult(
            source,
            playService.play(player, StringArgumentType.getString(context, "emote"))
        );
    }

    private static int stopEmote(
        CommandSourceStack source,
        PlaybackManager playbackManager,
        PermissionService permissionService
    ) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!permissionService.canStop(player)) {
            source.sendFailure(Component.literal("No stop permission."));
            return 0;
        }

        ActiveEmote activeEmote = playbackManager.stopEmote(player);
        if (activeEmote == null) {
            source.sendFailure(Component.literal("No active emote."));
            return 0;
        }

        source.sendSuccess(
            () -> Component.literal("Stop: " + activeEmote.namespace()),
            false
        );
        return 1;
    }

    private static ServerPlayer findPlayer(CommandSourceStack source) {
        Entity entity = source.getEntity();
        return entity instanceof ServerPlayer player ? player : null;
    }
}
