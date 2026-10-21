package io.github.hanhy06.emote.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.config.Config;
import io.github.hanhy06.emote.content.EmoteCatalog;
import io.github.hanhy06.emote.content.PreparedAnimation;
import io.github.hanhy06.emote.permission.PermissionService;
import io.github.hanhy06.emote.playback.PlayerPlaybackManager;
import io.github.hanhy06.emote.playback.stress.PlaybackStressTest;
import io.github.hanhy06.emote.playback.stress.PlaybackStressTestReport;
import io.github.hanhy06.emote.skin.model.PlayerSkinRegion;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.github.hanhy06.emote.playback.stress.PlaybackStressTest.DEFAULT_INSTANCE_COUNT;
import static io.github.hanhy06.emote.playback.stress.PlaybackStressTest.MAX_INSTANCE_COUNT;

final class StressTestCommand {
    private static final DynamicCommandExceptionType INVALID_LOAD = new DynamicCommandExceptionType(value -> Component.literal(
        "Invalid stress-test load '" + value + "'. Use a positive number followed by i or d."
    ));
    private final EmoteCatalog emoteCatalog;
    private final PlayerPlaybackManager playerPlaybackManager;
    private final PermissionService permissionService;

    StressTestCommand(
        EmoteCatalog emoteCatalog,
        PlayerPlaybackManager playerPlaybackManager,
        PermissionService permissionService
    ) {
        this.emoteCatalog = emoteCatalog;
        this.playerPlaybackManager = playerPlaybackManager;
        this.permissionService = permissionService;
    }

    LiteralArgumentBuilder<CommandSourceStack> createCommand() {
        return Commands.literal("stress-test")
            .requires(this.permissionService::canManage)
            .then(Commands.argument("time", TimeArgument.time(1))
                .executes(context -> startStressTest(
                    context.getSource(),
                    IntegerArgumentType.getInteger(context, "time"),
                    new StressLoad(DEFAULT_INSTANCE_COUNT, LoadUnit.INSTANCES)
                ))
                .then(Commands.argument("load", StringArgumentType.word())
                    .suggests((ignoredContext, builder) -> SharedSuggestionProvider.suggest(
                        List.of(Integer.toString(DEFAULT_INSTANCE_COUNT), DEFAULT_INSTANCE_COUNT + "i", Config.DEFAULT_MAX_ACTIVE_DISPLAY_ENTITIES + "d"),
                        builder
                    ))
                    .executes(context -> startStressTest(
                        context.getSource(),
                        IntegerArgumentType.getInteger(context, "time"),
                        parseLoad(StringArgumentType.getString(context, "load"))
                    ))))
            .then(Commands.literal("stop")
                .executes(context -> stopStressTest(context.getSource())));
    }

    private int startStressTest(CommandSourceStack source, int durationTicks, StressLoad load) {
        List<PreparedAnimation> animations = this.emoteCatalog.animations();
        if (animations.isEmpty()) {
            source.sendFailure(Component.literal("No emotes are registered."));
            return 0;
        }

        Map<String, Map<PlayerSkinRegion, String>> preparedSkin = null;
        if (source.getEntity() instanceof ServerPlayer player) {
            var skinPreparation = this.playerPlaybackManager.prepareStressTestSkin(player, animations);
            if (!skinPreparation.ready()) {
                source.sendFailure(Component.literal(
                    skinPreparation.preparing() ? "Preparing your skin… " + skinPreparation.progressPercent() + "% Run the stress test again when it is ready."
                        : "No complete player or default skin is available."
                ));
                return 0;
            }
            preparedSkin = skinPreparation.textures();
        }

        PlaybackStressTest.StartResult startResult;
        try {
            startResult = load.unit() == LoadUnit.INSTANCES
                ? this.playerPlaybackManager.engine().stressTest().start(
                    source.getLevel(),
                    source.getPosition(),
                    source.getRotation().y,
                    animations,
                    durationTicks,
                    load.amount(),
                    preparedSkin,
                    report -> sendStressTestReport(source, report)
                )
                : this.playerPlaybackManager.engine().stressTest().startByDisplayCount(
                    source.getLevel(),
                    source.getPosition(),
                    source.getRotation().y,
                    animations,
                    durationTicks,
                    load.amount(),
                    preparedSkin,
                    report -> sendStressTestReport(source, report)
                );
        } catch (RuntimeException exception) {
            EmoteMod.LOGGER.warn("Failed to start emote stress test", exception);
            source.sendFailure(Component.literal("Failed to start emote stress test."));
            return 0;
        }
        int gridSize = (int) Math.ceil(Math.sqrt(startResult.instanceCount()));
        String requestedLoad = load.unit() == LoadUnit.INSTANCES
            ? load.amount() + " instances"
            : load.amount() + " displays";
        source.sendSuccess(
            () -> Component.literal(
                "\n\n\nStarted a stress test for " + requestedLoad + ". Created " + startResult.instanceCount()
                    + " instances with " + startResult.displayEntityCount() + " displays in a " + gridSize + "×" + gridSize
                    + " grid for " + String.format(Locale.ROOT, "%.1f", durationTicks / 20.0D) + " seconds using "
                    + animations.size() + " emotes."
            ),
            true
        );
        return startResult.instanceCount();
    }

    static StressLoad parseLoad(String input) throws CommandSyntaxException {
        if (input == null || input.isEmpty()) throw INVALID_LOAD.create(input);
        char suffix = input.charAt(input.length() - 1);
        LoadUnit unit = suffix == 'd' ? LoadUnit.DISPLAYS : LoadUnit.INSTANCES;
        String number = suffix == 'd' || suffix == 'i' ? input.substring(0, input.length() - 1) : input;
        if (number.isEmpty() || !number.chars().allMatch(Character::isDigit)) throw INVALID_LOAD.create(input);

        int amount;
        try {
            amount = Integer.parseInt(number);
        } catch (NumberFormatException exception) {
            throw INVALID_LOAD.create(input);
        }
        if (amount < 1 || (unit == LoadUnit.INSTANCES && amount > MAX_INSTANCE_COUNT)) {
            throw INVALID_LOAD.create(input);
        }
        return new StressLoad(amount, unit);
    }

    enum LoadUnit {
        INSTANCES,
        DISPLAYS
    }

    record StressLoad(int amount, LoadUnit unit) {
    }

    private int stopStressTest(CommandSourceStack source) {
        PlaybackStressTestReport report = this.playerPlaybackManager.engine().stressTest().stop();
        if (report == null) {
            source.sendFailure(Component.literal("No emote stress test is running."));
            return 0;
        }

        sendStressTestReport(source, report);
        return report.activeInstances();
    }

    private void sendStressTestReport(CommandSourceStack source, PlaybackStressTestReport report) {
        var message = Component.literal("\n\n\n\n\nEmote stress test results")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal("\n• Instances: ").withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(report.activeInstances() + " / " + report.requestedInstances()).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("\n• Displays: ").withStyle(ChatFormatting.AQUA))
            .append(Component.literal(Integer.toString(report.peakDisplayEntities())).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("  Failed: ").withStyle(ChatFormatting.RED))
            .append(Component.literal(Integer.toString(report.failedInstances())).withStyle(ChatFormatting.WHITE))
            .append(createStressDurationSummary(report))
            .append(Component.literal("\n\nServer performance").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("\n• Observed TPS: ").withStyle(ChatFormatting.AQUA))
            .append(Component.literal(String.format(
                Locale.ROOT,
                "%.2f (%,d ticks / %.1f s)",
                report.observedTps(),
                report.completedTicks(),
                report.runtimeSeconds()
            )).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("\n• MSPT").withStyle(ChatFormatting.GREEN))
            .append(createStressStatistic("\n  ", "baseline", "%.2f ms", ChatFormatting.GRAY, report.baselineMspt()))
            .append(createStressStatistic("  ", "avg", "%.2f ms", ChatFormatting.GREEN, report.averageMspt()))
            .append(createStressStatistic("\n  ", "p95", "%.2f ms", ChatFormatting.GOLD, report.percentile95Mspt()))
            .append(createStressStatistic("  ", "max", "%.2f ms", ChatFormatting.RED, report.maximumMspt()))
            .append(Component.literal("\n\nEmote processing").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("\n• Tick: ").withStyle(ChatFormatting.GREEN))
            .append(Component.literal(String.format(
                Locale.ROOT,
                "avg %.3f ms / max %.3f ms",
                report.averageEmoteProcessingMillis(),
                report.maximumEmoteProcessingMillis()
            )).withStyle(ChatFormatting.WHITE));
        source.sendSuccess(() -> message, true);
    }

    static Component createStressDurationSummary(PlaybackStressTestReport report) {
        double elapsedSeconds = roundTenths(report.elapsedSeconds());
        double setupSeconds = roundTenths(report.creationMillis() / 1_000.0D);
        double emoteSeconds = roundTenths(report.emoteProcessingSeconds());
        double cleanupSeconds = roundTenths(report.cleanupMillis() / 1_000.0D);
        double serverAndIdleSeconds = Math.max(
            0.0D,
            elapsedSeconds
                - setupSeconds
                - emoteSeconds
                - cleanupSeconds
        );
        return Component.literal("\n• Duration: ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(String.format(Locale.ROOT, "%.1f s", elapsedSeconds)).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("\n  Setup ").withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(String.format(Locale.ROOT, "%.1f s", setupSeconds)).withStyle(ChatFormatting.WHITE))
            .append(Component.literal(" + Emote ").withStyle(ChatFormatting.GREEN))
            .append(Component.literal(String.format(Locale.ROOT, "%.1f s", emoteSeconds)).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("\n  + Server/idle ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(String.format(Locale.ROOT, "%.1f s", serverAndIdleSeconds)).withStyle(ChatFormatting.WHITE))
            .append(Component.literal(" + Cleanup ").withStyle(ChatFormatting.GOLD))
            .append(Component.literal(String.format(Locale.ROOT, "%.1f s", cleanupSeconds)).withStyle(ChatFormatting.WHITE));
    }

    private static double roundTenths(double value) {
        return Math.round(value * 10.0D) / 10.0D;
    }

    static MutableComponent createStressStatistic(
        String prefix,
        String label,
        String valueFormat,
        ChatFormatting labelColor,
        double value
    ) {
        return Component.literal(prefix + label + ": ").withStyle(labelColor)
            .append(Component.literal(String.format(Locale.ROOT, valueFormat, value)).withStyle(ChatFormatting.WHITE));
    }

}
