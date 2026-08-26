package io.github.hanhy06.emote.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.content.EmoteCatalog;
import io.github.hanhy06.emote.content.PlayableEmote;
import io.github.hanhy06.emote.content.PreparedAnimation;
import io.github.hanhy06.emote.permission.PermissionService;
import io.github.hanhy06.emote.playback.PlaybackEngine;
import io.github.hanhy06.emote.playback.stress.PlaybackStressTestReport;
import io.github.hanhy06.emote.server.ReloadResult;
import io.github.hanhy06.emote.server.ReloadService;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;

import static io.github.hanhy06.emote.playback.PlaybackEngine.DEFAULT_STRESS_TEST_INSTANCE_COUNT;
import static io.github.hanhy06.emote.playback.PlaybackEngine.MAX_STRESS_TEST_INSTANCE_COUNT;

public final class AdminCommand {
    private final EmoteCatalog emoteCatalog;
    private final PlaybackEngine playbackEngine;
    private final PermissionService permissionService;
    private final ReloadService reloadService;
    private final ConfigManager configManager;

    public AdminCommand(
        EmoteCatalog emoteCatalog,
        PlaybackEngine playbackEngine,
        PermissionService permissionService,
        ReloadService reloadService,
        ConfigManager configManager
    ) {
        this.emoteCatalog = emoteCatalog;
        this.playbackEngine = playbackEngine;
        this.permissionService = permissionService;
        this.reloadService = reloadService;
        this.configManager = configManager;
    }

    void attachTo(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(createListCommand())
            .then(createReloadCommand())
            .then(createStopPlayerCommand())
            .then(createStopAllCommand())
            .then(createStressTestCommand())
            .then(createEnableCommand())
            .then(createDisableCommand());
    }

    LiteralArgumentBuilder<CommandSourceStack> createReloadCommand() {
        return Commands.literal("reload")
            .requires(this.permissionService.requireManage())
            .executes(context -> reload(context.getSource()));
    }

    LiteralArgumentBuilder<CommandSourceStack> createListCommand() {
        return Commands.literal("list")
            .requires(this.permissionService.requireManage())
            .executes(context -> list(context.getSource()));
    }

    LiteralArgumentBuilder<CommandSourceStack> createStopAllCommand() {
        return Commands.literal("stop-all")
            .requires(this.permissionService.requireManage())
            .executes(context -> stopAll(context.getSource()));
    }

    LiteralArgumentBuilder<CommandSourceStack> createStopPlayerCommand() {
        return Commands.literal("stop")
            .then(Commands.argument("player", EntityArgument.player())
                .requires(this.permissionService.requireManage())
                .executes(this::stopPlayer));
    }

    LiteralArgumentBuilder<CommandSourceStack> createStressTestCommand() {
        return Commands.literal("stress-test")
            .requires(this.permissionService.requireManage())
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
            .requires(this.permissionService.requireManage())
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
            .requires(this.permissionService.requireManage())
            .then(Commands.argument("id", IdentifierArgument.id())
                .suggests((ignoredContext, builder) -> SharedSuggestionProvider.suggest(
                    this.emoteCatalog.fileEmotes().stream().map(PlayableEmote::id),
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
        source.sendSuccess(() -> createReloadSummary(result), true);
        return result.loadedEmoteCount();
    }

    static Component createReloadSummary(ReloadResult result) {
        ChatFormatting loadedCountColor = result.detectedFileCount() == result.loadedEmoteCount()
            ? ChatFormatting.GREEN
            : ChatFormatting.RED;
        return Component.empty()
            .append(Component.literal("Emotes reloaded").withStyle(ChatFormatting.AQUA))
            .append(Component.literal("\n ").withStyle(ChatFormatting.WHITE))
            .append(Component.literal(Integer.toString(result.loadedEmoteCount())).withStyle(loadedCountColor))
            .append(Component.literal(" of ").withStyle(ChatFormatting.WHITE))
            .append(Component.literal(Integer.toString(result.detectedFileCount())))
            .append(Component.literal(" files loaded · ").withStyle(ChatFormatting.WHITE))
            .append(Component.literal(Integer.toString(result.disabledEmoteCount())).withStyle(ChatFormatting.RED))
            .append(Component.literal(" disabled · ").withStyle(ChatFormatting.WHITE))
            .append(Component.literal(Integer.toString(result.permissionRuleCount())).withStyle(ChatFormatting.BLUE))
            .append(Component.literal(" permission rules").withStyle(ChatFormatting.WHITE));
    }

    private int list(CommandSourceStack source) {
        List<PlayableEmote> emotes = this.emoteCatalog.emotes();
        if (emotes.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No emotes are loaded."), false);
            return 0;
        }

        source.sendSuccess(
            () -> Component.literal("Emotes").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(" (" + emotes.size() + ")").withStyle(ChatFormatting.YELLOW)),
            false
        );

        for (PlayableEmote emote : emotes) {
            source.sendSystemMessage(createListEntry(
                emote.id(),
                emote.name(),
                emote.description(),
                emote.durationTicks(),
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
        int durationTicks,
        boolean standalone,
        boolean playerHidden
    ) {
        String availability = !standalone
            ? "Sequence only"
            : playerHidden ? "Hidden" : "Available";
        return Component.literal("\n• " + name).withStyle(ChatFormatting.WHITE)
            .append(Component.literal("  " + description).withStyle(ChatFormatting.GRAY))
            .append(Component.literal("\n  " + id).withStyle(ChatFormatting.AQUA))
            .append(Component.literal(String.format(Locale.ROOT, " · %.1f seconds · %s", durationTicks / 20.0D, availability))
                .withStyle(ChatFormatting.GRAY));
    }

    private int stopAll(CommandSourceStack source) {
        this.playbackEngine.stopAll();
        source.sendSuccess(() -> Component.literal("Stopped all active emotes."), true);
        return 1;
    }

    private int stopPlayer(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        var session = this.playbackEngine.stop(player);
        if (session == null) {
            source.sendFailure(Component.literal(player.getName().getString() + " is not playing an emote."));
            return 0;
        }

        PlayableEmote emote = this.emoteCatalog.find(session.id());
        String displayName = emote == null ? session.id() : emote.name();
        source.sendSuccess(
            () -> Component.literal("Stopped " + displayName + " for " + player.getName().getString() + "."),
            true
        );
        return 1;
    }

    private int startStressTest(CommandSourceStack source, int requestedInstanceCount) {
        List<PreparedAnimation> emotes = this.emoteCatalog.animations();
        if (emotes.isEmpty()) {
            source.sendFailure(Component.literal("No emotes are registered."));
            return 0;
        }

        int instanceCount;
        try {
            instanceCount = this.playbackEngine.startStressTest(
                source.getLevel(),
                source.getPosition(),
                source.getRotation().y,
                emotes,
                requestedInstanceCount
            );
        } catch (RuntimeException exception) {
            EmoteMod.LOGGER.warn("Failed to start emote stress test", exception);
            source.sendFailure(Component.literal("Failed to start emote stress test."));
            return 0;
        }
        int gridSize = (int) Math.ceil(Math.sqrt(instanceCount));
        source.sendSuccess(
            () -> Component.literal(
                "\n\n\nStarted a stress test with " + instanceCount + " instances in a " + gridSize + "×" + gridSize
                    + " grid using " + emotes.size() + " emotes."
            ),
            true
        );
        return instanceCount;
    }

    private int stopStressTest(CommandSourceStack source) {
        PlaybackStressTestReport report = this.playbackEngine.stopStressTest();
        if (report == null) {
            source.sendFailure(Component.literal("No emote stress test is running."));
            return 0;
        }

        var message = Component.literal("\n\n\n\n\nEmote stress test results")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal("\n• Instances: ").withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(report.activeInstances() + " / " + report.requestedInstances()).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("\n• Displays: ").withStyle(ChatFormatting.AQUA))
            .append(Component.literal(Integer.toString(report.peakDisplayEntities())).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("  Failed: ").withStyle(ChatFormatting.RED))
            .append(Component.literal(Integer.toString(report.failedInstances())).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("  Duration: ").withStyle(ChatFormatting.GOLD))
            .append(Component.literal(String.format(Locale.ROOT, "%.1f s", report.elapsedSeconds())).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("\n\nServer performance").withStyle(ChatFormatting.GRAY))
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
            .append(Component.literal("\n\nEmote processing").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("\n• Create: ").withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(String.format(Locale.ROOT, "%.2f ms", report.creationMillis())).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("  Tick: ").withStyle(ChatFormatting.GREEN))
            .append(Component.literal(String.format(
                Locale.ROOT,
                "%.3f ms avg / %.3f ms max",
                report.averageManagerCpuMillis(),
                report.maximumManagerCpuMillis()
            )).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("\n• Cleanup: ").withStyle(ChatFormatting.GOLD))
            .append(Component.literal(String.format(Locale.ROOT, "%.2f ms", report.cleanupMillis())).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("  Samples: ").withStyle(ChatFormatting.LIGHT_PURPLE))
            .append(Component.literal(Integer.toString(report.measuredServerTicks())).withStyle(ChatFormatting.WHITE));
        source.sendSuccess(() -> message, true);
        return report.activeInstances();
    }

    private int setEnabled(CommandSourceStack source, String id, boolean enabled) {
        if (enabled) {
            if (this.configManager.getAccessConfig().isEnabled(id)) {
                source.sendFailure(Component.literal("That emote is already enabled: " + id));
                return 0;
            }
        } else if (this.emoteCatalog.findFileEmote(id) == null) {
            source.sendFailure(Component.literal("That emote is not currently enabled: " + id));
            return 0;
        }

        if (!this.configManager.setEmoteEnabled(id, enabled)) {
            source.sendFailure(Component.literal("Could not save the emote settings. Check the server log."));
            return 0;
        }
        if (!enabled) {
            this.playbackEngine.stopById(id);
        }

        this.reloadService.reloadFromCommand();
        String action = enabled ? "Enabled" : "Disabled";
        source.sendSuccess(
            () -> Component.literal(action + " " + id + "."),
            true
        );
        return 1;
    }
}
