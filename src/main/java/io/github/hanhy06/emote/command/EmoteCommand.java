package io.github.hanhy06.emote.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.bdengine.BDEngineDatapackProcessor;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.dialog.EmoteDialogManager;
import io.github.hanhy06.emote.emote.EmoteDefinition;
import io.github.hanhy06.emote.emote.EmoteRegistry;
import io.github.hanhy06.emote.emote.PlayableEmoteSelection;
import io.github.hanhy06.emote.emote.PlayableEmoteSelectionResult;
import io.github.hanhy06.emote.emote.PlayableEmoteService;
import io.github.hanhy06.emote.network.EmoteWheelSyncService;
import io.github.hanhy06.emote.permission.EmotePermissionService;
import io.github.hanhy06.emote.playback.ActiveEmote;
import io.github.hanhy06.emote.playback.EmotePlaybackManager;
import io.github.hanhy06.emote.playback.EmotePlaybackStartResult;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.stream.Collectors;

public final class EmoteCommand {
	private EmoteCommand() {
	}

	public static void registerCommand(
		CommandDispatcher<CommandSourceStack> dispatcher,
		EmoteRegistry emoteRegistry,
		EmotePlaybackManager emotePlaybackManager,
		BDEngineDatapackProcessor bdEngineDatapackProcessor,
		ConfigManager configManager,
		EmoteDialogManager emoteDialogManager,
		PlayableEmoteService playableEmoteService,
		EmotePermissionService emotePermissionService,
		EmoteWheelSyncService emoteWheelSyncService
	) {
		dispatcher.register(createRootCommand(
			"emote",
			emoteRegistry,
			emotePlaybackManager,
			bdEngineDatapackProcessor,
			configManager,
			emoteDialogManager,
			playableEmoteService,
			emotePermissionService,
			emoteWheelSyncService
		));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> createRootCommand(
		String rootName,
		EmoteRegistry emoteRegistry,
		EmotePlaybackManager emotePlaybackManager,
		BDEngineDatapackProcessor bdEngineDatapackProcessor,
		ConfigManager configManager,
		EmoteDialogManager emoteDialogManager,
		PlayableEmoteService playableEmoteService,
		EmotePermissionService emotePermissionService,
		EmoteWheelSyncService emoteWheelSyncService
	) {
		return Commands.literal(rootName)
			.executes(context -> openMenu(context.getSource(), emoteDialogManager, emotePermissionService))
			.then(Commands.literal("menu")
				.requires(emotePermissionService.requireDialogOpen())
				.executes(context -> openMenu(context.getSource(), emoteDialogManager, emotePermissionService))
				.then(Commands.argument("page", IntegerArgumentType.integer(1))
					.executes(context -> openMenu(
						context.getSource(),
						emoteDialogManager,
						emotePermissionService,
						IntegerArgumentType.getInteger(context, "page")
					))))
			.then(Commands.literal("list")
				.requires(emotePermissionService.requireList())
				.executes(context -> listEmotes(context.getSource(), emoteRegistry)))
			.then(Commands.literal("reload")
				.requires(emotePermissionService.requireReload())
				.executes(context -> reloadEmotes(
					context.getSource(),
					emoteRegistry,
					bdEngineDatapackProcessor,
					configManager,
					emoteWheelSyncService
				)))
			.then(Commands.literal("play")
				.requires(emotePermissionService.requirePlay())
				.then(Commands.argument("emote", StringArgumentType.word())
					.suggests((context, builder) -> SharedSuggestionProvider.suggest(
						getSuggestedPlayNames(context.getSource(), emoteRegistry, playableEmoteService),
						builder
					))
					.executes(context -> playDefaultEmote(context, emotePlaybackManager, playableEmoteService))
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
						.executes(context -> playSelectedAnimation(context, emotePlaybackManager, playableEmoteService)))))
			.then(Commands.literal("stop")
				.requires(emotePermissionService.requireStop())
				.executes(context -> stopEmote(context.getSource(), emotePlaybackManager)));
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
		EmoteDialogManager emoteDialogManager,
		EmotePermissionService emotePermissionService
	) throws CommandSyntaxException {
		return openMenu(source, emoteDialogManager, emotePermissionService, 1);
	}

	private static int openMenu(
		CommandSourceStack source,
		EmoteDialogManager emoteDialogManager,
		EmotePermissionService emotePermissionService,
		int pageNumber
	) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		if (!emotePermissionService.canOpenDialog(player)) {
			source.sendFailure(Component.literal("No menu permission."));
			return 0;
		}

		emoteDialogManager.openDialog(player, pageNumber);
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
			String animationSummary = definition.animations().isEmpty()
				? "-"
				: definition.animations().stream()
					.map(animation -> animation.name() + "(" + animation.keyframeCount() + ")")
					.collect(Collectors.joining(", "));

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

	private static int reloadEmotes(
		CommandSourceStack source,
		EmoteRegistry emoteRegistry,
		BDEngineDatapackProcessor bdEngineDatapackProcessor,
		ConfigManager configManager,
		EmoteWheelSyncService emoteWheelSyncService
	) {
		boolean configLoaded = configManager.readConfig();
		Emote.getPlayerSkinManager().reloadHttpServer(source.getServer());
		boolean reloadedResources = bdEngineDatapackProcessor.enableEmoteDatapacks(source.getServer());
		int emoteCount = reloadedResources
			? emoteRegistry.size()
			: bdEngineDatapackProcessor.reloadServerEmotes(source.getServer());
		if (!reloadedResources) {
			emoteWheelSyncService.syncAll(source.getServer());
		}
		source.sendSuccess(
			() -> Component.literal(
				"Reloading: cfg=" + configLoaded
					+ ", emotes=" + emoteCount
					+ (reloadedResources ? " (resource reload)" : "")
			),
			true
		);
		return emoteCount;
	}

	private static int playEmote(
		CommandSourceStack source,
		ServerPlayer player,
		EmotePlaybackManager emotePlaybackManager,
		PlayableEmoteSelectionResult selectionResult
	) {
		if (!selectionResult.isSuccess()) {
			source.sendFailure(Component.literal(selectionResult.errorMessage()));
			return 0;
		}

		PlayableEmoteSelection selection = selectionResult.selection();

		EmotePlaybackStartResult playResult = emotePlaybackManager.startEmote(player, selection.definition(), selection.animation());
		if (!playResult.isSuccess()) {
			source.sendFailure(Component.literal(playResult.errorMessage()));
			return 0;
		}

		String displayName = selection.definition().createDisplayName(selection.animation().name());
		source.sendSuccess(
			() -> Component.literal("Play: " + displayName),
			false
		);
		return 1;
	}

	private static int playDefaultEmote(
		CommandContext<CommandSourceStack> context,
		EmotePlaybackManager emotePlaybackManager,
		PlayableEmoteService playableEmoteService
	) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayerOrException();
		return playEmote(
			source,
			player,
			emotePlaybackManager,
			playableEmoteService.findDefaultSelection(player, StringArgumentType.getString(context, "emote"))
		);
	}

	private static int playSelectedAnimation(
		CommandContext<CommandSourceStack> context,
		EmotePlaybackManager emotePlaybackManager,
		PlayableEmoteService playableEmoteService
	) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayerOrException();
		return playEmote(
			source,
			player,
			emotePlaybackManager,
			playableEmoteService.findSelection(
				player,
				StringArgumentType.getString(context, "emote"),
				StringArgumentType.getString(context, "animation")
			)
		);
	}

	private static int stopEmote(CommandSourceStack source, EmotePlaybackManager emotePlaybackManager) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		ActiveEmote activeEmote = emotePlaybackManager.stopEmote(player);
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
