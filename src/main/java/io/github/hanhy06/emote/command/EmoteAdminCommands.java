package io.github.hanhy06.emote.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.emote.EmoteRegistry;
import io.github.hanhy06.emote.emote.RegisteredEmote;
import io.github.hanhy06.emote.permission.PermissionService;
import io.github.hanhy06.emote.playback.PlaybackLoadTestReport;
import io.github.hanhy06.emote.playback.PlaybackManager;
import io.github.hanhy06.emote.server.EmoteReloadResult;
import io.github.hanhy06.emote.server.EmoteReloadService;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

import static io.github.hanhy06.emote.playback.PlaybackManager.DEFAULT_LOAD_TEST_INSTANCE_COUNT;
import static io.github.hanhy06.emote.playback.PlaybackManager.MAX_LOAD_TEST_INSTANCE_COUNT;

final class EmoteAdminCommands {
    private final EmoteRegistry emoteRegistry;
    private final PlaybackManager playbackManager;
    private final PermissionService permissionService;
    private final EmoteReloadService reloadService;
    private final ConfigManager configManager;

    EmoteAdminCommands(
        EmoteRegistry emoteRegistry,
        PlaybackManager playbackManager,
        PermissionService permissionService,
        EmoteReloadService reloadService,
        ConfigManager configManager
    ) {
        this.emoteRegistry = emoteRegistry;
        this.playbackManager = playbackManager;
        this.permissionService = permissionService;
        this.reloadService = reloadService;
        this.configManager = configManager;
    }

    LiteralArgumentBuilder<CommandSourceStack> createReloadCommand() {
        return Commands.literal("reload")
            .requires(this.permissionService.requireReload())
            .executes(context -> reloadEmotes(context.getSource()));
    }

    LiteralArgumentBuilder<CommandSourceStack> createListCommand() {
        return Commands.literal("list")
            .requires(this.permissionService.requireReload())
            .executes(context -> listEmotes(context.getSource()));
    }

    LiteralArgumentBuilder<CommandSourceStack> createStopAllCommand() {
        return Commands.literal("stop-all")
            .requires(this.permissionService.requireGameMaster())
            .executes(context -> stopAllEmotes(context.getSource()));
    }

    LiteralArgumentBuilder<CommandSourceStack> createLoadTestCommand() {
        return Commands.literal("load-test")
            .requires(this.permissionService.requireGameMaster())
            .executes(context -> startLoadTest(context.getSource(), DEFAULT_LOAD_TEST_INSTANCE_COUNT))
            .then(Commands.argument(
                    "count",
                    IntegerArgumentType.integer(1, MAX_LOAD_TEST_INSTANCE_COUNT)
                )
                .executes(context -> startLoadTest(
                    context.getSource(),
                    IntegerArgumentType.getInteger(context, "count")
                )))
            .then(Commands.literal("stop")
                .executes(context -> stopLoadTest(context.getSource())));
    }

    LiteralArgumentBuilder<CommandSourceStack> createEnableCommand() {
        return Commands.literal("enable")
            .requires(this.permissionService.requireGameMaster())
            .then(Commands.argument("id", IdentifierArgument.id())
                .suggests((ignoredContext, builder) -> SharedSuggestionProvider.suggest(
                    this.configManager.getEmoteAccessConfig().disabled(),
                    builder
                ))
                .executes(context -> setEmoteEnabled(
                    context.getSource(),
                    IdentifierArgument.getId(context, "id").toString(),
                    true
                )));
    }

    LiteralArgumentBuilder<CommandSourceStack> createDisableCommand() {
        return Commands.literal("disable")
            .requires(this.permissionService.requireGameMaster())
            .then(Commands.argument("id", IdentifierArgument.id())
                .suggests((ignoredContext, builder) -> SharedSuggestionProvider.suggest(
                    this.emoteRegistry.getFileEmotes().stream().map(RegisteredEmote::id),
                    builder
                ))
                .executes(context -> setEmoteEnabled(
                    context.getSource(),
                    IdentifierArgument.getId(context, "id").toString(),
                    false
                )));
    }

    private int reloadEmotes(CommandSourceStack source) {
        EmoteReloadResult result = this.reloadService.reloadFromCommand();
        source.sendSuccess(
            () -> Component.literal(
                "Reloading: cfg=" + result.configLoaded()
                    + ", access=" + result.emoteAccessConfigLoaded()
                    + ", emotes=" + result.emoteCount()
            ),
            true
        );
        return result.emoteCount();
    }

    private int listEmotes(CommandSourceStack source) {
        List<RegisteredEmote> emotes = this.emoteRegistry.getAll();
        if (emotes.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No emotes."), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Emotes: " + emotes.size()), false);

        for (RegisteredEmote emote : emotes) {
            source.sendSystemMessage(Component.literal(
                "- " + emote.id()
                    + " name=" + emote.name()
                    + " nodes=" + emote.nodeCount()
                    + " source=" + emote.sourcePath().getFileName()
            ));
        }

        return emotes.size();
    }

    private int stopAllEmotes(CommandSourceStack source) {
        this.playbackManager.stopAllEmotes();
        source.sendSuccess(() -> Component.literal("Stopped all emotes."), true);
        return 1;
    }

    private int startLoadTest(CommandSourceStack source, int requestedInstanceCount) {
        List<RegisteredEmote> emotes = this.emoteRegistry.getAll();
        if (emotes.isEmpty()) {
            source.sendFailure(Component.literal("No emotes are registered."));
            return 0;
        }

        int instanceCount;
        try {
            instanceCount = this.playbackManager.startLoadTest(
                source.getLevel(),
                source.getPosition(),
                source.getRotation().y,
                emotes,
                requestedInstanceCount
            );
        } catch (RuntimeException exception) {
            Emote.LOGGER.warn("Failed to start emote load test", exception);
            source.sendFailure(Component.literal("Failed to start emote load test."));
            return 0;
        }
        int gridSize = (int)Math.ceil(Math.sqrt(instanceCount));
        source.sendSuccess(
            () -> Component.literal(
                "Started load test: instances=" + instanceCount
                    + ", grid=" + gridSize + "x" + gridSize
                    + ", emotes=" + emotes.size()
                    + ", initialTicks=80..175"
            ),
            true
        );
        return instanceCount;
    }

    private int stopLoadTest(CommandSourceStack source) {
        PlaybackLoadTestReport report = this.playbackManager.stopLoadTest();
        if (report == null) {
            source.sendFailure(Component.literal("No emote load test is running."));
            return 0;
        }

        var message = Component.literal("-- Emote Load Test Result --")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal("\n• Instances: ").withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(report.activeInstances() + " / " + report.requestedInstances()).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("\n• Displays: ").withStyle(ChatFormatting.AQUA))
            .append(Component.literal(Integer.toString(report.peakDisplayEntities())).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("  Failed: ").withStyle(ChatFormatting.RED))
            .append(Component.literal(Integer.toString(report.failedInstances())).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("  Duration: ").withStyle(ChatFormatting.GOLD))
            .append(Component.literal(String.format(Locale.ROOT, "%.1fs", report.elapsedSeconds())).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("\n\n-- Server Performance --").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("\n• TPS: ").withStyle(ChatFormatting.AQUA))
            .append(Component.literal(String.format(
                Locale.ROOT,
                "%.2f -> %.2f  (min %.2f, drop %.2f)",
                report.baselineTps(),
                report.averageTps(),
                report.minimumTps(),
                report.tpsDrop()
            )).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("\n• MSPT: ").withStyle(ChatFormatting.GREEN))
            .append(Component.literal(String.format(
                Locale.ROOT,
                "%.2f -> %.2f  (max %.2f)",
                report.baselineMspt(),
                report.averageMspt(),
                report.maximumMspt()
            )).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("\n\n-- Emote Processing --").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("\n• Create: ").withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(String.format(Locale.ROOT, "%.2fms", report.creationMillis())).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("  Tick: ").withStyle(ChatFormatting.GREEN))
            .append(Component.literal(String.format(
                Locale.ROOT,
                "%.3fms avg / %.3fms max",
                report.averageManagerCpuMillis(),
                report.maximumManagerCpuMillis()
            )).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("\n• Cleanup: ").withStyle(ChatFormatting.GOLD))
            .append(Component.literal(String.format(Locale.ROOT, "%.2fms", report.cleanupMillis())).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("  Samples: ").withStyle(ChatFormatting.LIGHT_PURPLE))
            .append(Component.literal(Integer.toString(report.measuredServerTicks())).withStyle(ChatFormatting.WHITE));
        source.sendSuccess(() -> message, true);
        return report.activeInstances();
    }

    private int setEmoteEnabled(CommandSourceStack source, String id, boolean enabled) {
        if (enabled) {
            if (this.configManager.getEmoteAccessConfig().isEnabled(id)) {
                source.sendFailure(Component.literal("Emote is not disabled: " + id));
                return 0;
            }
        } else if (this.emoteRegistry.findFile(id) == null) {
            source.sendFailure(Component.literal("Emote is not enabled: " + id));
            return 0;
        }

        if (!this.configManager.setEmoteEnabled(id, enabled)) {
            source.sendFailure(Component.literal("Failed to save emotes.json."));
            return 0;
        }
        if (!enabled) {
            this.playbackManager.stopId(id);
        }

        EmoteReloadResult reloadResult = this.reloadService.reloadFromCommand();
        String action = enabled ? "Enabled" : "Disabled";
        source.sendSuccess(
            () -> Component.literal(action + " emote: " + id + " (emotes=" + reloadResult.emoteCount() + ")"),
            true
        );
        return 1;
    }
}
