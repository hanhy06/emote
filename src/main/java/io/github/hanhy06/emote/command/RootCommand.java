package io.github.hanhy06.emote.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.dialog.DialogManager;
import io.github.hanhy06.emote.emote.*;
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
    private final EmoteRegistry emoteRegistry;
    private final PlaybackManager playbackManager;
    private final DialogManager dialogManager;
    private final PlayableEmoteService playableEmoteService;
    private final PlayService playService;
    private final PermissionService permissionService;
    private final EmoteReloadService reloadService;
    private final ConfigManager configManager;

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
        this.emoteRegistry = emoteRegistry;
        this.playbackManager = playbackManager;
        this.dialogManager = dialogManager;
        this.playableEmoteService = playableEmoteService;
        this.playService = playService;
        this.permissionService = permissionService;
        this.reloadService = reloadService;
        this.configManager = configManager;
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
            .then(createListCommand())
            .then(createReloadCommand())
            .then(createPlayCommand())
            .then(createStopCommand())
            .then(createStopAllCommand())
            .then(createEnableCommand())
            .then(createDisableCommand());
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

    private LiteralArgumentBuilder<CommandSourceStack> createListCommand() {
        return Commands.literal("list")
            .executes(context -> listEmotes(context.getSource()));
    }

    private LiteralArgumentBuilder<CommandSourceStack> createReloadCommand() {
        return Commands.literal("reload")
            .requires(this.permissionService.requireReload())
            .executes(context -> reloadEmotes(context.getSource()));
    }

    private LiteralArgumentBuilder<CommandSourceStack> createPlayCommand() {
        return Commands.literal("play")
            .then(Commands.argument("emote", StringArgumentType.word())
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

    private LiteralArgumentBuilder<CommandSourceStack> createStopAllCommand() {
        return Commands.literal("stop-all")
            .requires(this.permissionService.requireGameMaster())
            .executes(context -> stopAllEmotes(context.getSource()));
    }

    private LiteralArgumentBuilder<CommandSourceStack> createEnableCommand() {
        return Commands.literal("enable")
            .requires(this.permissionService.requireGameMaster())
            .then(Commands.argument("namespace", StringArgumentType.word())
                .suggests((ignoredContext, builder) -> SharedSuggestionProvider.suggest(
                    getDisabledNamespaces(),
                    builder
                ))
                .executes(context -> setPackEnabled(
                    context.getSource(),
                    StringArgumentType.getString(context, "namespace"),
                    true
                )));
    }

    private LiteralArgumentBuilder<CommandSourceStack> createDisableCommand() {
        return Commands.literal("disable")
            .requires(this.permissionService.requireGameMaster())
            .then(Commands.argument("namespace", StringArgumentType.word())
                .suggests((ignoredContext, builder) -> SharedSuggestionProvider.suggest(
                    this.emoteRegistry.getDefinitions().stream().map(EmoteDefinition::namespace),
                    builder
                ))
                .executes(context -> setPackEnabled(
                    context.getSource(),
                    StringArgumentType.getString(context, "namespace"),
                    false
                )));
    }

    private List<String> getSuggestedPlayNames(CommandSourceStack source) {
        ServerPlayer player = findPlayer(source);
        return player == null
            ? this.playableEmoteService.getPlayNames()
            : this.playableEmoteService.getPlayablePlayNames(player);
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

    private int listEmotes(CommandSourceStack source) {
        List<EmoteDefinition> definitions = this.emoteRegistry.getDefinitions();
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

    private int reloadEmotes(CommandSourceStack source) {
        if (Emote.SERVER == null) {
            source.sendFailure(Component.literal("Server unavailable."));
            return 0;
        }

        EmoteReloadResult result = this.reloadService.reloadFromCommand();
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

    private int playEmote(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        return applyPlayResult(
            source,
            this.playService.play(player, StringArgumentType.getString(context, "emote"))
        );
    }

    private int stopEmote(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ActiveEmote activeEmote = this.playbackManager.stopEmote(player);
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

    private int stopAllEmotes(CommandSourceStack source) {
        this.playbackManager.stopAllEmotes();
        source.sendSuccess(() -> Component.literal("Stopped all emotes."), true);
        return 1;
    }

    private int setPackEnabled(CommandSourceStack source, String namespace, boolean enabled) {
        if (enabled) {
            if (this.configManager.getPackConfig().isEnabled(namespace)) {
                source.sendFailure(Component.literal("Emote is not disabled: " + namespace));
                return 0;
            }
        } else if (this.emoteRegistry.findDefinition(namespace) == null) {
            source.sendFailure(Component.literal("Emote is not enabled: " + namespace));
            return 0;
        }

        if (!this.configManager.setPackEnabled(namespace, enabled)) {
            source.sendFailure(Component.literal("Failed to save packs.json."));
            return 0;
        }
        if (!enabled) {
            this.playbackManager.stopNamespace(namespace);
        }

        EmoteReloadResult reloadResult = this.reloadService.reloadFromCommand();
        String action = enabled ? "Enabled" : "Disabled";
        source.sendSuccess(
            () -> Component.literal(action + " emote: " + namespace + " (emotes=" + reloadResult.emoteCount() + ")"),
            true
        );
        return 1;
    }

    private List<String> getDisabledNamespaces() {
        return this.configManager.getPackConfig().disabled();
    }

    private static ServerPlayer findPlayer(CommandSourceStack source) {
        Entity entity = source.getEntity();
        return entity instanceof ServerPlayer player ? player : null;
    }
}
