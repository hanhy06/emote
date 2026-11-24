package io.github.hanhy06.emote.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.hanhy06.emote.network.NetworkBenchmark;
import io.github.hanhy06.emote.permission.PermissionService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.network.chat.Component;

import java.util.Locale;

final class NetworkTestCommand {
    static LiteralArgumentBuilder<CommandSourceStack> createCommand() {
        return Commands.literal("network-test")
            .requires(PermissionService.CAN_MANAGE)
            .then(Commands.argument("time", TimeArgument.time(1))
                .executes(context -> start(context.getSource(), IntegerArgumentType.getInteger(context, "time"))))
            .then(Commands.literal("stop").executes(context -> stop(context.getSource())));
    }

    private static int start(CommandSourceStack source, int durationTicks) {
        if (!NetworkBenchmark.INSTANCE.start(source.getServer(), durationTicks, result -> sendReport(source, result))) {
            source.sendFailure(Component.literal("An emote network test is already running."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Started an emote network test for " + durationTicks + " server ticks."), false);
        return 1;
    }

    private static int stop(CommandSourceStack source) {
        if (!NetworkBenchmark.INSTANCE.stop(source.getServer())) {
            source.sendFailure(Component.literal("No emote network test is running."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Stopped the emote network test."), false);
        return 1;
    }

    private static void sendReport(CommandSourceStack source, NetworkBenchmark.Result result) {
        var report = result.report();
        var traffic = report.traffic();
        var displays = report.virtualDisplays();
        double seconds = report.wallSeconds();
        long nettyCpuNanos = report.eventLoopCpu().stream().mapToLong(NetworkBenchmark.EventLoopCpu::cpuNanos).sum();
        String nettyCpu = report.eventLoopCpu().isEmpty()
            ? "unavailable"
            : String.format(Locale.ROOT, "%.2f ms", nettyCpuNanos / 1.0e6);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
            "Emote network test: %.2f s, %d TCP connections, %,d outbound frames (%,d deflated), %.2f KiB/s queued, p95 %.2f MSPT (%d ticks)"
                + "\nStage time: encode %.2f ms, compression %.2f ms, framing %.2f ms, flush %.2f ms. Netty thread CPU: %s."
                + "\nVirtual displays: %,d codec calls, %,d compressions, %,d fallback packets."
                + "\nReport: %s",
            seconds, report.connections().size(), traffic.frames(), traffic.deflatedFrames(), report.transport().writeBytes() / 1024.0 / seconds,
            report.ticks().p95Mspt(), report.ticks().samples(), traffic.encodeNanos() / 1.0e6, traffic.compressionNanos() / 1.0e6,
            traffic.frameNanos() / 1.0e6, report.transport().flushNanos() / 1.0e6, nettyCpu,
            displays.codecCalls(), displays.compressionCalls(), displays.fallbackPackets(),
            result.path() == null ? "could not be saved" : result.path().toAbsolutePath()
        )), false);
    }
}
