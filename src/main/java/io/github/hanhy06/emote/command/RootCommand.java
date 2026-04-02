package io.github.hanhy06.emote.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.bdengine.BDEngineDatapackProcessor;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.dialog.DialogManager;
import io.github.hanhy06.emote.emote.EmoteAnimation;
import io.github.hanhy06.emote.emote.EmoteDefinition;
import io.github.hanhy06.emote.emote.EmoteRegistry;
import io.github.hanhy06.emote.emote.PlayableEmoteService;
import io.github.hanhy06.emote.network.service.PlayResult;
import io.github.hanhy06.emote.network.service.PlayService;
import io.github.hanhy06.emote.network.service.WheelSyncService;
import io.github.hanhy06.emote.permission.PermissionService;
import io.github.hanhy06.emote.playback.PlaybackManager;
import io.github.hanhy06.emote.playback.data.ActiveEmote;
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
            BDEngineDatapackProcessor bdEngineDatapackProcessor,
            ConfigManager configManager,
            DialogManager dialogManager,
            PlayableEmoteService playableEmoteService,
            PlayService playService,
            PermissionService permissionService,
            WheelSyncService wheelSyncService
    ) {
        CommandRegistrationCallback.EVENT.register((dispatcher, ignoredRegistryAccess, ignoredEnvironment) ->
                dispatcher.register(createRootCommand(
                        emoteRegistry,
                        playbackManager,
                        bdEngineDatapackProcessor,
                        configManager,
                        dialogManager,
                        playableEmoteService,
                        playService,
                        permissionService,
                        wheelSyncService
                ))
        );
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createRootCommand(
            EmoteRegistry emoteRegistry,
            PlaybackManager playbackManager,
            BDEngineDatapackProcessor bdEngineDatapackProcessor,
            ConfigManager configManager,
            DialogManager dialogManager,
            PlayableEmoteService playableEmoteService,
            PlayService playService,
            PermissionService permissionService,
            WheelSyncService wheelSyncService
    ) {
        return Commands.literal("emote")
                .executes(context -> openMenu(context.getSource(), dialogManager, permissionService))
                .then(createMenuCommand(dialogManager, permissionService))
                .then(createListCommand(emoteRegistry, permissionService))
                .then(createReloadCommand(
                        emoteRegistry,
                        bdEngineDatapackProcessor,
                        configManager,
                        permissionService,
                        wheelSyncService
                ))
                .then(createPlayCommand(emoteRegistry, playableEmoteService, playService, permissionService))
                .then(createStopCommand(playbackManager, permissionService));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createMenuCommand(
            DialogManager dialogManager,
            PermissionService permissionService
    ) {
        return Commands.literal("menu")
                .requires(permissionService.requireDialogOpen())
                .executes(context -> openMenu(context.getSource(), dialogManager, permissionService))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(context -> openMenu(
                                context.getSource(),
                                dialogManager,
                                permissionService,
                                IntegerArgumentType.getInteger(context, "page")
                        )));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createListCommand(
            EmoteRegistry emoteRegistry,
            PermissionService permissionService
    ) {
        return Commands.literal("list")
                .requires(permissionService.requireList())
                .executes(context -> listEmotes(context.getSource(), emoteRegistry));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createReloadCommand(
            EmoteRegistry emoteRegistry,
            BDEngineDatapackProcessor bdEngineDatapackProcessor,
            ConfigManager configManager,
            PermissionService permissionService,
            WheelSyncService wheelSyncService
    ) {
        return Commands.literal("reload")
                .requires(permissionService.requireReload())
                .executes(context -> reloadEmotes(
                        context.getSource(),
                        emoteRegistry,
                        bdEngineDatapackProcessor,
                        configManager,
                        wheelSyncService
                ));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createPlayCommand(
            EmoteRegistry emoteRegistry,
            PlayableEmoteService playableEmoteService,
            PlayService playService,
            PermissionService permissionService
    ) {
        return Commands.literal("play")
                .requires(permissionService.requirePlay())
                .then(Commands.argument("emote", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                getSuggestedPlayNames(context.getSource(), emoteRegistry, playableEmoteService),
                                builder
                        ))
                        .executes(context -> playDefaultEmote(context, playService))
                        .then(Commands.argument("animation", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        getSuggestedAnimationNames(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "emote"),
                                                emoteRegistry,
                                                playableEmoteService
                                        ),
                                        builder
                                ))
                                .executes(context -> playSelectedAnimation(context, playService))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createStopCommand(
            PlaybackManager playbackManager,
            PermissionService permissionService
    ) {
        return Commands.literal("stop")
                .requires(permissionService.requireStop())
                .executes(context -> stopEmote(context.getSource(), playbackManager));
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

    private static List<String> getSuggestedAnimationNames(
            CommandSourceStack source,
            String commandNameOrNamespace,
            EmoteRegistry emoteRegistry,
            PlayableEmoteService playableEmoteService
    ) {
        ServerPlayer player = findPlayer(source);
        return player == null
                ? emoteRegistry.getAnimationNamesForPlay(commandNameOrNamespace)
                : playableEmoteService.getPlayableAnimationNamesForPlay(player, commandNameOrNamespace);
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

    private static int listEmotes(CommandSourceStack source, EmoteRegistry emoteRegistry) {
        List<EmoteDefinition> definitions = emoteRegistry.getDefinitions();
        if (definitions.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No emotes."), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Emotes: " + definitions.size()), false);

        for (EmoteDefinition definition : definitions) {
            String animationSummary = createAnimationSummary(definition);

            source.sendSystemMessage(Component.literal(
                    "- " + definition.namespace()
                            + " cmd=" + definition.commandName()
                            + " default=" + definition.defaultAnimationName()
                            + " name=" + definition.name()
                            + " parts=" + definition.partCount()
                            + " clips=" + animationSummary
            ));
        }

        return definitions.size();
    }

    private static String createAnimationSummary(EmoteDefinition definition) {
        List<EmoteAnimation> animations = definition.animations();
        if (animations.isEmpty()) {
            return "-";
        }

        StringBuilder summary = new StringBuilder();
        for (EmoteAnimation animation : animations) {
            if (!summary.isEmpty()) {
                summary.append(", ");
            }

            summary.append(animation.name())
                    .append("(")
                    .append(animation.keyframeCount())
                    .append(")");
        }

        return summary.toString();
    }

    private static int reloadEmotes(
            CommandSourceStack source,
            EmoteRegistry emoteRegistry,
            BDEngineDatapackProcessor bdEngineDatapackProcessor,
            ConfigManager configManager,
            WheelSyncService wheelSyncService
    ) {
        if (Emote.SERVER == null) {
            source.sendFailure(Component.literal("Server unavailable."));
            return 0;
        }

        boolean configLoaded = configManager.readConfig();
        boolean identifierConfigLoaded = configManager.readIdentifierConfig();
        Emote.SKIN_MANAGER.reloadHttpServer();
        boolean reloadedResources = bdEngineDatapackProcessor.enableEmoteDatapacks();
        int emoteCount = reloadedResources
                ? emoteRegistry.size()
                : bdEngineDatapackProcessor.reloadServerEmotes();
        if (!reloadedResources) {
            wheelSyncService.syncAll();
        }
        source.sendSuccess(
                () -> Component.literal(
                        "Reloading: cfg=" + configLoaded
                                + ", identifier=" + identifierConfigLoaded
                                + ", emotes=" + emoteCount
                                + (reloadedResources ? " (resource reload)" : "")
                ),
                true
        );
        return emoteCount;
    }

    private static int applyPlayResult(CommandSourceStack source, PlayResult playResult) {
        if (!playResult.isSuccess()) {
            source.sendFailure(Component.literal(playResult.errorMessage()));
            return 0;
        }

        source.sendSuccess(
                () -> Component.literal("Play: " + playResult.displayName()),
                false
        );
        return 1;
    }

    private static int playDefaultEmote(
            CommandContext<CommandSourceStack> context,
            PlayService playService
    ) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        return applyPlayResult(
                source,
                playService.playDefault(player, StringArgumentType.getString(context, "emote"))
        );
    }

    private static int playSelectedAnimation(
            CommandContext<CommandSourceStack> context,
            PlayService playService
    ) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        return applyPlayResult(
                source,
                playService.playSelection(
                        player,
                        StringArgumentType.getString(context, "emote"),
                        StringArgumentType.getString(context, "animation")
                )
        );
    }

    private static int stopEmote(CommandSourceStack source, PlaybackManager playbackManager) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ActiveEmote activeEmote = playbackManager.stopEmote(player);
        if (activeEmote == null) {
            source.sendFailure(Component.literal("No active emote."));
            return 0;
        }

        source.sendSuccess(
                () -> Component.literal("Stop: " + activeEmote.namespace() + ":" + activeEmote.animationName()),
                false
        );
        return 1;
    }

    private static ServerPlayer findPlayer(CommandSourceStack source) {
        Entity entity = source.getEntity();
        return entity instanceof ServerPlayer player ? player : null;
    }
}
