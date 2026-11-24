package io.github.hanhy06.emote.command;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.config.JsonFileStore;
import io.github.hanhy06.emote.content.EmoteCatalog;
import io.github.hanhy06.emote.content.PreparedAnimation;
import io.github.hanhy06.emote.network.NetworkBenchmark;
import io.github.hanhy06.emote.permission.PermissionService;
import io.github.hanhy06.emote.playback.PlaybackEngine;
import io.github.hanhy06.emote.playback.stress.PlaybackStressTestReport;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class BenchmarkCommand {
    private static final List<Integer> LOADS = List.of(30, 50, 70, 100);
    private static final int IDLE_SECONDS = 10;
    private static final int LOAD_SECONDS = 30;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    private final EmoteCatalog catalog;
    private final PlaybackEngine playbackEngine;
    private @Nullable Run active;

    BenchmarkCommand(EmoteCatalog catalog, PlaybackEngine playbackEngine) {
        this.catalog = catalog;
        this.playbackEngine = playbackEngine;
    }

    LiteralArgumentBuilder<CommandSourceStack> createCommand() {
        return Commands.literal("benchmark")
            .requires(PermissionService.CAN_MANAGE)
            .then(Commands.argument("viewers", IntegerArgumentType.integer(1))
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(List.of("50", "100"), builder))
                .executes(context -> start(context.getSource(), IntegerArgumentType.getInteger(context, "viewers"))))
            .then(Commands.literal("stop").executes(context -> {
                if (!isRunning()) return fail(context.getSource(), "No emote benchmark is running.");
                NetworkBenchmark.INSTANCE.stop(context.getSource().getServer());
                return 1;
            }));
    }

    boolean isRunning() {
        return this.active != null;
    }

    private int start(CommandSourceStack source, int viewers) {
        if (isRunning() || this.playbackEngine.stressTest().isRunning() || NetworkBenchmark.INSTANCE.isRunning()) {
            return fail(source, "Stop the active emote test before starting a benchmark.");
        }
        MinecraftServer server = source.getServer();
        var players = server.getPlayerList().getPlayers();
        if (players.size() != viewers) return fail(source, "Expected " + viewers + " viewers. Connected players: " + players.size() + ".");
        var animations = List.copyOf(this.catalog.animations());
        if (animations.isEmpty()) return fail(source, "No emote animations are registered.");

        try {
            Path debug = Files.createDirectories(server.getServerDirectory().resolve("debug"));
            Path directory = Files.createTempDirectory(debug, "emote-benchmark-" + viewers + "-viewers-");
            var run = new Run(source, directory, animations);
            writeSummary(run, "running");
            this.active = run;
            source.sendSuccess(() -> Component.literal("Started benchmark for " + viewers + " viewers. Reports: " + directory.toAbsolutePath()), false);
            startIdle(run);
            return 1;
        } catch (IOException exception) {
            EmoteMod.LOGGER.warn("Could not create emote benchmark reports", exception);
            return fail(source, "Could not create emote benchmark reports. Check the server log.");
        }
    }

    private void startIdle(Run run) {
        int instances = LOADS.get(run.results.size());
        run.source.sendSuccess(() -> Component.literal("Benchmark " + instances + " emotes: " + IDLE_SECONDS + " s idle, then " + LOAD_SECONDS + " s playback."), false);
        NetworkBenchmark.INSTANCE.startForSeconds(run.source.getServer(), IDLE_SECONDS, idle -> startLoad(run, instances, idle));
    }

    private void startLoad(Run run, int instances, NetworkBenchmark.Report idle) {
        String issue = captureIssue(run, idle, "idle");
        if (issue != null) {
            finishCase(run, new CaseReport(instances, idle, null, null, issue));
            return;
        }
        try {
            var source = run.source;
            this.playbackEngine.stressTest().start(source.getLevel(), source.getPosition(), source.getRotation().y, run.animations,
                Integer.MAX_VALUE, instances, null, ignored -> {});
        } catch (RuntimeException exception) {
            EmoteMod.LOGGER.warn("Could not start playback in emote benchmark", exception);
            finishCase(run, new CaseReport(instances, idle, null, null, "Could not start playback. See the server log."));
            return;
        }
        NetworkBenchmark.INSTANCE.startForSeconds(run.source.getServer(), LOAD_SECONDS, load -> finishLoad(run, instances, idle, load));
    }

    private void finishLoad(Run run, int instances, NetworkBenchmark.Report idle, NetworkBenchmark.Report load) {
        PlaybackStressTestReport playback = this.playbackEngine.stressTest().stop();
        String issue = captureIssue(run, load, "playback");
        if (issue == null && (playback == null || playback.activeInstances() != instances || playback.failedInstances() > 0)) {
            issue = "The requested number of emotes did not remain active.";
        }
        finishCase(run, new CaseReport(instances, idle, load, playback, issue));
    }

    private static @Nullable String captureIssue(Run run, NetworkBenchmark.Report report, String phase) {
        if (!report.completed()) return "Capture stopped during " + phase + ".";
        if (report.playersAtStart() != run.viewers.size() || !run.viewers.equals(viewers(run.source.getServer()))) {
            return "The set of viewers changed during " + phase + ".";
        }
        if (report.connections().stream().filter(connection -> connection.player() != null).count() != run.viewers.size()) {
            return "The capture did not observe one TCP connection per viewer during " + phase + ".";
        }
        return null;
    }

    private void finishCase(Run run, CaseReport report) {
        String filename = report.instances + "-emotes.json";
        boolean hasNext = report.issue == null && run.results.size() + 1 < LOADS.size();
        try {
            JsonFileStore.writeObjectAtomically(run.directory.resolve(filename), GSON.toJsonTree(report).getAsJsonObject(), GSON);
            run.results.add(new CaseSummary(report.instances, filename, report.issue, PhaseSummary.of(report.idle), PhaseSummary.of(report.load), report.playback));
            writeSummary(run, hasNext ? "running" : report.issue == null ? "complete" : "stopped");
        } catch (IOException exception) {
            this.active = null;
            EmoteMod.LOGGER.warn("Could not write the emote benchmark case", exception);
            fail(run.source, "Could not write the emote benchmark case. Check the server log.");
            return;
        }

        if (hasNext) {
            startIdle(run);
        } else {
            this.active = null;
            Path summary = run.directory.resolve("summary.json").toAbsolutePath();
            if (report.issue == null) {
                run.source.sendSuccess(() -> Component.literal("Emote benchmark complete. Summary: " + summary), false);
            } else {
                fail(run.source, "Emote benchmark stopped. " + report.issue + " Partial results: " + summary);
            }
        }
    }

    private static void writeSummary(Run run, String status) throws IOException {
        var source = run.source;
        var origin = source.getPosition();
        var summary = new Summary(status, run.startedAt, SharedConstants.getCurrentVersion().name(), Runtime.version().toString(),
            run.viewerNames.size(), run.viewerNames, IDLE_SECONDS, LOAD_SECONDS, LOADS, run.animations.size(), source.getLevel().dimension().identifier().toString(),
            new double[]{origin.x, origin.y, origin.z}, source.getRotation().y, run.results);
        JsonFileStore.writeObjectAtomically(run.directory.resolve("summary.json"), GSON.toJsonTree(summary).getAsJsonObject(), GSON);
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message));
        return 0;
    }

    private static Map<UUID, SocketAddress> viewers(MinecraftServer server) {
        Map<UUID, SocketAddress> viewers = new HashMap<>();
        server.getPlayerList().getPlayers().forEach(player -> viewers.put(player.getUUID(), player.connection.getRemoteAddress()));
        return viewers;
    }

    private static final class Run {
        private final CommandSourceStack source;
        private final Path directory;
        private final List<PreparedAnimation> animations;
        private final Map<UUID, SocketAddress> viewers;
        private final List<String> viewerNames;
        private final List<CaseSummary> results = new ArrayList<>();
        private final String startedAt = Instant.now().toString();

        private Run(CommandSourceStack source, Path directory, List<PreparedAnimation> animations) {
            this.source = source;
            this.directory = directory;
            this.animations = animations;
            this.viewers = viewers(source.getServer());
            this.viewerNames = source.getServer().getPlayerList().getPlayers().stream().map(ServerPlayer::getScoreboardName).sorted().toList();
        }
    }

    private record CaseReport(int instances, NetworkBenchmark.Report idle, NetworkBenchmark.@Nullable Report load,
                              @Nullable PlaybackStressTestReport playback, @Nullable String issue) {}

    private record CaseSummary(int instances, String report, @Nullable String issue, PhaseSummary idle, @Nullable PhaseSummary load,
                               @Nullable PlaybackStressTestReport playback) {}

    private record PhaseSummary(double wallSeconds, double tps, double p95Mspt, double p99Mspt, double maxMspt,
                                double queuedBytesPerSecond, long peakSampledHeapBytes, @Nullable Long processCpuNanos) {
        private static @Nullable PhaseSummary of(NetworkBenchmark.@Nullable Report report) {
            if (report == null) return null;
            return new PhaseSummary(report.wallSeconds(), report.ticks().observedTps(), report.ticks().p95Mspt(), report.ticks().p99Mspt(),
                report.ticks().maxMspt(), report.transport().writeBytes() / report.wallSeconds(), report.jvm().peakSampledHeapUsedBytes(),
                report.jvm().processCpuNanos());
        }
    }

    private record Summary(String status, String startedAt, String minecraftVersion, String javaVersion, int viewerCount, List<String> viewers,
                           int idleSeconds, int loadSeconds, List<Integer> requestedInstances, int availableAnimations,
                           String dimension, double[] origin, float yaw, List<CaseSummary> cases) {}
}
