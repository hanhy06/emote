package io.github.hanhy06.emot.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.hanhy06.emot.bdengine.BDEngineDatapackProcessor;
import io.github.hanhy06.emot.config.ConfigManager;
import io.github.hanhy06.emot.dialog.EmoteDialogManager;
import io.github.hanhy06.emot.emote.EmoteDefinition;
import io.github.hanhy06.emot.emote.EmoteRegistry;
import io.github.hanhy06.emot.permission.EmotePermissionService;
import io.github.hanhy06.emot.playback.ActiveEmote;
import io.github.hanhy06.emot.playback.EmotePlaybackManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;
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
		EmotePermissionService emotePermissionService
	) {
		dispatcher.register(Commands.literal("emote")
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
				.executes(context -> reloadEmotes(context.getSource(), bdEngineDatapackProcessor, configManager)))
			.then(Commands.literal("play")
				.requires(emotePermissionService.requirePlay())
				.then(Commands.argument("namespace", StringArgumentType.word())
					.suggests((context, builder) -> SharedSuggestionProvider.suggest(emoteRegistry.getNamespaces(), builder))
					.then(Commands.argument("animation", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(
							emoteRegistry.getAnimationNames(StringArgumentType.getString(context, "namespace")),
							builder
						))
						.executes(context -> playEmote(context, emoteRegistry, emotePlaybackManager, emotePermissionService)))))
			.then(Commands.literal("stop")
				.requires(emotePermissionService.requireStop())
				.executes(context -> stopEmote(context.getSource(), emotePlaybackManager))));
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
			source.sendFailure(Component.literal("You do not have permission to open the emote menu."));
			return 0;
		}

		emoteDialogManager.openDialog(player, pageNumber);
		return 1;
	}

	private static int listEmotes(CommandSourceStack source, EmoteRegistry emoteRegistry) {
		List<EmoteDefinition> definitions = emoteRegistry.getDefinitions();
		if (definitions.isEmpty()) {
			source.sendSuccess(() -> Component.literal("No emotes were loaded from datapacks."), false);
			return 0;
		}

		source.sendSuccess(() -> Component.literal("Loaded " + definitions.size() + " emotes."), false);

		for (EmoteDefinition definition : definitions) {
			String animationSummary = definition.animations().isEmpty()
				? "no animations"
				: definition.animations().stream()
					.map(animation -> animation.name() + "(" + animation.keyframeCount() + ")")
					.collect(Collectors.joining(", "));

			source.sendSystemMessage(Component.literal(
				"- " + definition.namespace()
					+ " parts=" + definition.partCount()
					+ " animations=" + animationSummary
			));
		}

		return definitions.size();
	}

	private static int reloadEmotes(
		CommandSourceStack source,
		BDEngineDatapackProcessor bdEngineDatapackProcessor,
		ConfigManager configManager
	) {
		boolean configLoaded = configManager.readConfig();
		int emoteCount = bdEngineDatapackProcessor.reloadServerEmotes(source.getServer());
		source.sendSuccess(
			() -> Component.literal("Reloaded config=" + configLoaded + ", emotes=" + emoteCount + "."),
			true
		);
		return emoteCount;
	}

	private static int playEmote(
		CommandContext<CommandSourceStack> context,
		EmoteRegistry emoteRegistry,
		EmotePlaybackManager emotePlaybackManager,
		EmotePermissionService emotePermissionService
	) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayerOrException();
		String namespace = StringArgumentType.getString(context, "namespace");
		String animationName = StringArgumentType.getString(context, "animation");

		Optional<EmoteDefinition> definition = emoteRegistry.findDefinition(namespace);
		if (definition.isEmpty()) {
			source.sendFailure(Component.literal("Unknown emote: " + namespace));
			return 0;
		}

		if (definition.get().findAnimation(animationName).isEmpty()) {
			source.sendFailure(Component.literal("Unknown animation: " + namespace + ":" + animationName));
			return 0;
		}

		if (!emotePermissionService.canPlay(player, namespace, animationName)) {
			source.sendFailure(Component.literal("You do not have permission to play that emote."));
			return 0;
		}

		emotePlaybackManager.startEmote(player, namespace, animationName);
		source.sendSuccess(
			() -> Component.literal("Started emote session for " + namespace + ":" + animationName + ". Visual playback is not implemented yet."),
			false
		);
		return 1;
	}

	private static int stopEmote(CommandSourceStack source, EmotePlaybackManager emotePlaybackManager) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<ActiveEmote> activeEmote = emotePlaybackManager.stopEmote(player.getUUID());
		if (activeEmote.isEmpty()) {
			source.sendFailure(Component.literal("You do not have an active emote session."));
			return 0;
		}

		ActiveEmote removedEmote = activeEmote.get();
		source.sendSuccess(
			() -> Component.literal("Stopped " + removedEmote.namespace() + ":" + removedEmote.animationName() + "."),
			false
		);
		return 1;
	}
}
