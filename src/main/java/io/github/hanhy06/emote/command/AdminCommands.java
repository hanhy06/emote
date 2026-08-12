package io.github.hanhy06.emote.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.emote.EmoteRegistry;
import io.github.hanhy06.emote.emote.EmoteDefinition;
import io.github.hanhy06.emote.emote.RegisteredEmote;
import io.github.hanhy06.emote.permission.PermissionService;
import io.github.hanhy06.emote.playback.PlaybackStressTestReport;
import io.github.hanhy06.emote.playback.PlaybackManager;
import io.github.hanhy06.emote.server.ReloadResult;
import io.github.hanhy06.emote.server.ReloadService;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

import static io.github.hanhy06.emote.playback.PlaybackManager.DEFAULT_STRESS_TEST_INSTANCE_COUNT;
import static io.github.hanhy06.emote.playback.PlaybackManager.MAX_STRESS_TEST_INSTANCE_COUNT;

final class AdminCommands {
    private final EmoteRegistry emoteRegistry;
    private final PlaybackManager playbackManager;
    private final PermissionService permissionService;
    private final ReloadService reloadService;
    private final ConfigManager configManager;

    AdminCommands(
        EmoteRegistry emoteRegistry,
        PlaybackManager playbackManager,
        PermissionService permissionService,
        ReloadService reloadService,
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
            .executes(context -> reload(context.getSource()));
    }

    LiteralArgumentBuilder<CommandSourceStack> createListCommand() {
        return Commands.literal("list")
            .requires(this.permissionService.requireReload())
            .executes(context -> list(context.getSource()));
    }

    LiteralArgumentBuilder<CommandSourceStack> createStopAllCommand() {
        return Commands.literal("stop-all")
            .requires(this.permissionService.requireGameMaster())
            .executes(context -> stopAll(context.getSource()));
    }

    LiteralArgumentBuilder<CommandSourceStack> createStressTestCommand() {
        return Commands.literal("stress-test")
            .requires(this.permissionService.requireGameMaster())
            .executes(context -> startStressTest(context.getSource(), DEFAULT_STRESS_TEST_INSTANCE_COUNT))
            .then(Commands.argument(
                    "count",
                    IntegerArgumentType.integer(1, MAX_STRESS_TEST_INSTANCE_COUNT)
                )
                .executes(context -> startStressTest(
                    context.getSource(),
                    IntegerArgumentType.getInteger(context, "count")
                )))
            .then(Commands.literal("stop")
                .executes(context -> stopStressTest(context.getSource())));
    }

    LiteralArgumentBuilder<CommandSourceStack> createEnableCommand() {
        return Commands.literal("enable")
            .requires(this.permissionService.requireGameMaster())
            .then(Commands.argument("id", IdentifierArgument.id())
                .suggests((ignoredContext, builder) -> SharedSuggestionProvider.suggest(
                    this.configManager.getAccessConfig().disabled(),
                    builder
                ))
                .executes(context -> setEnabled(
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
                    this.emoteRegistry.getFileDefinitions().stream().map(EmoteDefinition::id),
                    builder
                ))
                .executes(context -> setEnabled(
                    context.getSource(),
                    IdentifierArgument.getId(context, "id").toString(),
                    false
                )));
    }

    private int reload(CommandSourceStack source) {
        ReloadResult result = this.reloadService.reloadFromCommand();
        source.sendSuccess(
            () -> Component.literal(
                "Reloading: cfg=" + result.configLoaded()
                    + ", access=" + result.accessConfigLoaded()
                    + ", emotes=" + result.emoteCount()
            ),
            true
        );
        return result.emoteCount();
    }

    private int list(CommandSourceStack source) {
        List<EmoteDefinition> emotes = this.emoteRegistry.getAllDefinitions();
        if (emotes.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No emotes."), false);
            return 0;
        }

        source.sendSuccess(
            () -> Component.literal("Emotes").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal(" (" + emotes.size() + ")").withStyle(ChatFormatting.YELLOW)),
            false
        );

        for (EmoteDefinition emote : emotes) {
            source.sendSystemMessage(createListEntry(
                emote.id(),
                emote.name(),
                emote.description(),
                emote.nodeCount(),
                emote.durationTicks(),
                emote.sourcePath().getFileName().toString(),
                emote.loopMode().name().toLowerCase(Locale.ROOT),
                emote.standalone(),
                emote.playerBehavior().hidden()
            ));
        }

        return emotes.size();
    }

    static Component createListEntry(
        String id,
        String name,
        String description,
        int nodeCount,
        int durationTicks,
        String sourceFileName,
        String loopMode,
        boolean standalone,
        boolean playerHidden
    ) {
        return Component.literal("\n• ").withStyle(ChatFormatting.DARK_GRAY)
            .append(Component.literal(id).withStyle(ChatFormatting.AQUA))
            .append(Component.literal("\n  " + name).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD))
            .append(Component.literal(" — " + description).withStyle(ChatFormatting.GRAY))
            .append(Component.literal("\n  Nodes: ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(Integer.toString(nodeCount)).withStyle(ChatFormatting.GREEN))
            .append(Component.literal("  Time: ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(String.format(Locale.ROOT, "%d ticks (%.1fs)", durationTicks, durationTicks / 20.0D))
                .withStyle(ChatFormatting.GOLD))
            .append(Component.literal("\n  Source: ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(sourceFileName).withStyle(ChatFormatting.YELLOW))
            .append(Component.literal("\n  Loop: ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(loopMode).withStyle(ChatFormatting.LIGHT_PURPLE))
            .append(Component.literal("  Standalone: ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(standalone ? "yes" : "no").withStyle(standalone ? ChatFormatting.GREEN : ChatFormatting.RED))
            .append(Component.literal("  Player: ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(playerHidden ? "hidden" : "visible").withStyle(ChatFormatting.AQUA));
    }

    private int stopAll(CommandSourceStack source) {
        this.playbackManager.stopAll();
        source.sendSuccess(() -> Component.literal("Stopped all emotes."), true);
        return 1;
    }

    private int startStressTest(CommandSourceStack source, int requestedInstanceCount) {
        List<RegisteredEmote> emotes = this.emoteRegistry.getAll();
        if (emotes.isEmpty()) {
            source.sendFailure(Component.literal("No emotes are registered."));
            return 0;
        }

        int instanceCount;
        try {
            instanceCount = this.playbackManager.startStressTest(
                source.getLevel(),
                source.getPosition(),
                source.getRotation().y,
                emotes,
                requestedInstanceCount
            );
        } catch (RuntimeException exception) {
            Emote.LOGGER.warn("Failed to start emote stress test", exception);
            source.sendFailure(Component.literal("Failed to start emote stress test."));
            return 0;
        }
        int gridSize = (int) Math.ceil(Math.sqrt(instanceCount));
        source.sendSuccess(
            () -> Component.literal(
                "Started stress test: instances=" + instanceCount
                    + ", grid=" + gridSize + "x" + gridSize
                    + ", emotes=" + emotes.size()
                    + ", initialTicks=80..175"
            ),
            true
        );
        return instanceCount;
    }

    private int stopStressTest(CommandSourceStack source) {
        PlaybackStressTestReport report = this.playbackManager.stopStressTest();
        if (report == null) {
            source.sendFailure(Component.literal("No emote stress test is running."));
            return 0;
        }

        var message = Component.literal("\n\n\n\n\n-- Emote Stress Test Result --")
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

    private int setEnabled(CommandSourceStack source, String id, boolean enabled) {
        if (enabled) {
            if (this.configManager.getAccessConfig().isEnabled(id)) {
                source.sendFailure(Component.literal("Emote is not disabled: " + id));
                return 0;
            }
        } else if (this.emoteRegistry.findFileDefinition(id) == null) {
            source.sendFailure(Component.literal("Emote is not enabled: " + id));
            return 0;
        }

        if (!this.configManager.setEmoteEnabled(id, enabled)) {
            source.sendFailure(Component.literal("Failed to save emotes.json."));
            return 0;
        }
        if (!enabled) {
            this.playbackManager.stopById(id);
        }

        ReloadResult reloadResult = this.reloadService.reloadFromCommand();
        String action = enabled ? "Enabled" : "Disabled";
        source.sendSuccess(
            () -> Component.literal(action + " emote: " + id + " (emotes=" + reloadResult.emoteCount() + ")"),
            true
        );
        return 1;
    }
}
