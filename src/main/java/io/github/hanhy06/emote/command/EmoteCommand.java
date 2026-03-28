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
import io.github.hanhy06.emote.emote.EmoteAnimation;
import io.github.hanhy06.emote.emote.EmoteDefinition;
import io.github.hanhy06.emote.emote.EmoteRegistry;
import io.github.hanhy06.emote.permission.EmotePermissionService;
import io.github.hanhy06.emote.playback.ActiveEmote;
import io.github.hanhy06.emote.playback.EmotePlaybackManager;
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
		dispatcher.register(createRootCommand(
			"emote",
			emoteRegistry,
			emotePlaybackManager,
			bdEngineDatapackProcessor,
			configManager,
			emoteDialogManager,
			emotePermissionService
		));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> createRootCommand(
		String rootName,
		EmoteRegistry emoteRegistry,
		EmotePlaybackManager emotePlaybackManager,
		BDEngineDatapackProcessor bdEngineDatapackProcessor,
		ConfigManager configManager,
		EmoteDialogManager emoteDialogManager,
		EmotePermissionService emotePermissionService
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
				.executes(context -> reloadEmotes(context.getSource(), bdEngineDatapackProcessor, configManager)))
			.then(Commands.literal("play")
				.requires(emotePermissionService.requirePlay())
				.then(Commands.argument("emote", StringArgumentType.word())
					.suggests((context, builder) -> SharedSuggestionProvider.suggest(emoteRegistry.getPlayNames(), builder))
					.executes(context -> playDefaultEmote(context, emoteRegistry, emotePlaybackManager, emotePermissionService))
					.then(Commands.argument("animation", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(
							emoteRegistry.getAnimationNamesForPlay(StringArgumentType.getString(context, "emote")),
							builder
						))
						.executes(context -> playSelectedAnimation(context, emoteRegistry, emotePlaybackManager, emotePermissionService)))))
			.then(Commands.literal("stop")
				.requires(emotePermissionService.requireStop())
				.executes(context -> stopEmote(context.getSource(), emotePlaybackManager)));
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
		BDEngineDatapackProcessor bdEngineDatapackProcessor,
		ConfigManager configManager
	) {
		boolean configLoaded = configManager.readConfig();
		Emote.getPlayerSkinManager().reloadHttpServer(source.getServer());
		bdEngineDatapackProcessor.enableEmoteDatapacks(source.getServer());
		int emoteCount = bdEngineDatapackProcessor.reloadServerEmotes(source.getServer());
		source.sendSuccess(
			() -> Component.literal("Reloading: cfg=" + configLoaded + ", emotes=" + emoteCount),
			true
		);
		return emoteCount;
	}

	private static int playEmote(
		CommandSourceStack source,
		EmoteRegistry emoteRegistry,
		EmotePlaybackManager emotePlaybackManager,
		EmotePermissionService emotePermissionService,
		String emoteName,
		String animationName
	) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();

		Optional<EmoteDefinition> definition = emoteRegistry.findDefinitionForPlay(emoteName);
		if (definition.isEmpty()) {
			source.sendFailure(Component.literal("Unknown: " + emoteName));
			return 0;
		}

		Optional<EmoteAnimation> animation = definition.get().findAnimation(animationName);
		if (animation.isEmpty()) {
			source.sendFailure(Component.literal("Unknown: " + definition.get().commandName() + ":" + animationName));
			return 0;
		}

		if (!emotePermissionService.canPlay(player, definition.get().namespace(), animationName)) {
			source.sendFailure(Component.literal("No emote permission."));
			return 0;
		}

		Optional<String> playError = emotePlaybackManager.startEmote(player, definition.get(), animation.get());
		if (playError.isPresent()) {
			source.sendFailure(Component.literal(playError.get()));
			return 0;
		}

		String displayName = definition.get().createDisplayName(animationName);
		source.sendSuccess(
			() -> Component.literal("Play: " + displayName),
			false
		);
		return 1;
	}

	private static int playDefaultEmote(
		CommandContext<CommandSourceStack> context,
		EmoteRegistry emoteRegistry,
		EmotePlaybackManager emotePlaybackManager,
		EmotePermissionService emotePermissionService
	) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		String emoteName = StringArgumentType.getString(context, "emote");
		Optional<EmoteDefinition> definition = emoteRegistry.findDefinitionForPlay(emoteName);
		if (definition.isEmpty()) {
			source.sendFailure(Component.literal("Unknown: " + emoteName));
			return 0;
		}

		Optional<EmoteAnimation> defaultAnimation = definition.get().findDefaultAnimation();
		if (defaultAnimation.isEmpty()) {
			source.sendFailure(Component.literal("No default: " + definition.get().commandName()));
			return 0;
		}

		return playEmote(
			source,
			emoteRegistry,
			emotePlaybackManager,
			emotePermissionService,
			emoteName,
			defaultAnimation.get().name()
		);
	}

	private static int playSelectedAnimation(
		CommandContext<CommandSourceStack> context,
		EmoteRegistry emoteRegistry,
		EmotePlaybackManager emotePlaybackManager,
		EmotePermissionService emotePermissionService
	) throws CommandSyntaxException {
		return playEmote(
			context.getSource(),
			emoteRegistry,
			emotePlaybackManager,
			emotePermissionService,
			StringArgumentType.getString(context, "emote"),
			StringArgumentType.getString(context, "animation")
		);
	}

	private static int stopEmote(CommandSourceStack source, EmotePlaybackManager emotePlaybackManager) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<ActiveEmote> activeEmote = emotePlaybackManager.stopEmote(player);
		if (activeEmote.isEmpty()) {
			source.sendFailure(Component.literal("No active emote."));
			return 0;
		}

		ActiveEmote removedEmote = activeEmote.get();
		source.sendSuccess(
			() -> Component.literal("Stop: " + removedEmote.namespace() + ":" + removedEmote.animationName()),
			false
		);
		return 1;
	}
}
