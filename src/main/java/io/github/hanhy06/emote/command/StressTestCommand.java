package io.github.hanhy06.emote.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.content.EmoteCatalog;
import io.github.hanhy06.emote.content.PreparedAnimation;
import io.github.hanhy06.emote.permission.PermissionService;
import io.github.hanhy06.emote.playback.PlaybackEngine;
import io.github.hanhy06.emote.playback.stress.PlaybackStressTestReport;
import io.github.hanhy06.emote.skin.model.PlayerSkinPreparation;
import io.github.hanhy06.emote.skin.model.PreparedPlayerSkin;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;

import static io.github.hanhy06.emote.playback.PlaybackEngine.*;

final class StressTestCommand {
    private final EmoteCatalog emoteCatalog;
    private final PlaybackEngine playbackEngine;
    private final PermissionService permissionService;

    StressTestCommand(
        EmoteCatalog emoteCatalog,
        PlaybackEngine playbackEngine,
        PermissionService permissionService
    ) {
        this.emoteCatalog = emoteCatalog;
        this.playbackEngine = playbackEngine;
        this.permissionService = permissionService;
    }

    LiteralArgumentBuilder<CommandSourceStack> createCommand() {
        return Commands.literal("stress-test")
            .requires(this.permissionService.requireManage())
            .then(Commands.argument("time", TimeArgument.time(1))
                .executes(context -> startStressTest(
                    context.getSource(),
                    IntegerArgumentType.getInteger(context, "time"),
                    DEFAULT_STRESS_TEST_INSTANCE_COUNT,
                    DEFAULT_STRESS_TEST_PACKET_FANOUT
                ))
                .then(Commands.argument(
                        "count",
                        IntegerArgumentType.integer(1, MAX_STRESS_TEST_INSTANCE_COUNT)
                    )
                    .executes(context -> startStressTest(
                        context.getSource(),
                        IntegerArgumentType.getInteger(context, "time"),
                        IntegerArgumentType.getInteger(context, "count"),
                        DEFAULT_STRESS_TEST_PACKET_FANOUT
                    ))
                    .then(Commands.argument(
                            "packets",
                            IntegerArgumentType.integer(0, MAX_STRESS_TEST_PACKET_FANOUT)
                        )
                        .executes(context -> startStressTest(
                            context.getSource(),
                            IntegerArgumentType.getInteger(context, "time"),
                            IntegerArgumentType.getInteger(context, "count"),
                            IntegerArgumentType.getInteger(context, "packets")
                        )))))
            .then(Commands.literal("stop")
                .executes(context -> stopStressTest(context.getSource())));
    }

    private int startStressTest(CommandSourceStack source, int durationTicks, int requestedInstanceCount, int packetFanout) {
        List<PreparedAnimation> emotes = this.emoteCatalog.animations();
        if (emotes.isEmpty()) {
            source.sendFailure(Component.literal("No emotes are registered."));
            return 0;
        }

        PreparedPlayerSkin preparedSkin = null;
        if (source.getEntity() instanceof ServerPlayer player) {
            PlayerSkinPreparation skinPreparation = this.playbackEngine.prepareStressTestSkin(player, emotes);
            if (skinPreparation.preparing()) {
                source.sendFailure(Component.literal(
                    "Preparing your skin… " + skinPreparation.progressPercent() + "% Run the stress test again when it is ready."
                ));
                return 0;
            }
            preparedSkin = skinPreparation.preparedPlayerSkin();
        }

        int instanceCount;
        try {
            instanceCount = this.playbackEngine.startStressTest(
                source.getLevel(),
                source.getPosition(),
                source.getRotation().y,
                emotes,
                durationTicks,
                requestedInstanceCount,
                packetFanout,
                preparedSkin,
                report -> sendStressTestReport(source, report)
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
                    + " grid for " + String.format(Locale.ROOT, "%.1f", durationTicks / 20.0D) + " seconds using "
                    + emotes.size() + " emotes and " + packetFanout + "× packet fanout."
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
            )).withStyle(ChatFormatting.WHITE))
            .append(createPacketLoadSummary(report));
        source.sendSuccess(() -> message, true);
    }

    static Component createStressDurationSummary(PlaybackStressTestReport report) {
        double elapsedSeconds = roundTenths(report.elapsedSeconds());
        double setupSeconds = roundTenths(report.creationMillis() / 1_000.0D);
        double emoteSeconds = roundTenths(report.emoteProcessingSeconds());
        double networkSeconds = roundTenths(report.networkProcessingSeconds());
        double cleanupSeconds = roundTenths(report.cleanupMillis() / 1_000.0D);
        double serverAndIdleSeconds = Math.max(
            0.0D,
            elapsedSeconds
                - setupSeconds
                - emoteSeconds
                - networkSeconds
                - cleanupSeconds
        );
        return Component.literal("\n• Duration: ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(String.format(Locale.ROOT, "%.1f s", elapsedSeconds)).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("\n  Setup ").withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(String.format(Locale.ROOT, "%.1f s", setupSeconds)).withStyle(ChatFormatting.WHITE))
            .append(Component.literal(" + Emote ").withStyle(ChatFormatting.GREEN))
            .append(Component.literal(String.format(Locale.ROOT, "%.1f s", emoteSeconds)).withStyle(ChatFormatting.WHITE))
            .append(Component.literal(" + Network ").withStyle(ChatFormatting.AQUA))
            .append(Component.literal(String.format(Locale.ROOT, "%.1f s", networkSeconds)).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("\n  + Server/idle ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(String.format(Locale.ROOT, "%.1f s", serverAndIdleSeconds)).withStyle(ChatFormatting.WHITE))
            .append(Component.literal(" + Cleanup ").withStyle(ChatFormatting.GOLD))
            .append(Component.literal(String.format(Locale.ROOT, "%.1f s", cleanupSeconds)).withStyle(ChatFormatting.WHITE));
    }

    private static double roundTenths(double value) {
        return Math.round(value * 10.0D) / 10.0D;
    }

    static Component createPacketLoadSummary(PlaybackStressTestReport report) {
        var packets = report.packetLoad();
        if (packets.packetFanout() == 0) {
            return Component.literal("\n\nPacket load\n• Fanout: disabled").withStyle(ChatFormatting.GRAY);
        }

        double runtimeSeconds = Math.max(report.runtimeSeconds(), 0.001D);
        double packetsPerSecond = packets.runtimePackets() / runtimeSeconds;
        double mebibytesPerSecond = packets.runtimeBytes() / 1_048_576.0D / runtimeSeconds;
        double encodingShare = report.networkProcessingSeconds() == 0.0D
            ? 0.0D
            : Math.min(100.0D, packets.runtimeEncodingNanos() / 1_000_000_000.0D / report.networkProcessingSeconds() * 100.0D);
        double averageMebibytesPerTick = packets.runtimeSamples() == 0
            ? 0.0D
            : packets.runtimeBytes() / 1_048_576.0D / packets.runtimeSamples();
        return Component.literal("\n\nPacket load").withStyle(ChatFormatting.GRAY)
            .append(Component.literal("\n• Fanout: ").withStyle(ChatFormatting.AQUA))
            .append(Component.literal(packets.packetFanout() + "×").withStyle(ChatFormatting.WHITE))
            .append(Component.literal("  Create: ").withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(String.format(
                Locale.ROOT,
                "%,d packets / %.2f MiB",
                packets.creationPackets(),
                packets.creationBytes() / 1_048_576.0D
            )).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("\n• Tick: ").withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(String.format(
                Locale.ROOT,
                "avg %.3f ms / max %.3f ms",
                report.averageNetworkProcessingMillis(),
                report.maximumNetworkProcessingMillis()
            )).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("\n  Encoding share: ").withStyle(ChatFormatting.GOLD))
            .append(Component.literal(String.format(Locale.ROOT, "%.1f%%", encodingShare)).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("\n• Throughput: ").withStyle(ChatFormatting.GREEN))
            .append(Component.literal(String.format(
                Locale.ROOT,
                "%,.0f packets/s / %.2f MiB/s",
                packetsPerSecond,
                mebibytesPerSecond
            )).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("\n• Traffic/tick: ").withStyle(ChatFormatting.LIGHT_PURPLE))
            .append(Component.literal(String.format(
                Locale.ROOT,
                "avg %.2f MiB / max %.2f MiB",
                averageMebibytesPerTick,
                packets.maximumRuntimeBytesPerTick() / 1_048_576.0D
            )).withStyle(ChatFormatting.WHITE));
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
