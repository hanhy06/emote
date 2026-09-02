package io.github.hanhy06.emote.command;

import com.google.gson.JsonElement;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.content.EmoteCatalog;
import io.github.hanhy06.emote.content.PlayableEmote;
import io.github.hanhy06.emote.content.PreparedAnimation;
import io.github.hanhy06.emote.permission.PermissionService;
import io.github.hanhy06.emote.playback.PlaybackEngine;
import io.github.hanhy06.emote.playback.stress.PlaybackStressTestReport;
import io.github.hanhy06.emote.server.ReloadResult;
import io.github.hanhy06.emote.server.ReloadService;
import io.github.hanhy06.emote.skin.model.PlayerSkinPreparation;
import io.github.hanhy06.emote.skin.model.PreparedPlayerSkin;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.github.hanhy06.emote.playback.PlaybackEngine.*;

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
                emote.metadata(),
                emote.durationTicks(),
                emote.standalone()
            ));
        }

        return emotes.size();
    }

    static Component createListEntry(
        String id,
        EmoteMetadata metadata,
        int durationTicks,
        boolean standalone
    ) {
        var entry = Component.literal("\n• " + metadata.name()).withStyle(ChatFormatting.WHITE)
            .append(Component.literal("  " + metadata.description()).withStyle(ChatFormatting.GRAY))
            .append(Component.literal("\n  " + id).withStyle(ChatFormatting.AQUA))
            .append(Component.literal(String.format(Locale.ROOT, " · %.1f seconds", durationTicks / 20.0D))
                .withStyle(ChatFormatting.GRAY));
        if (!standalone) {
            entry.append(Component.literal(" · Sequence only").withStyle(ChatFormatting.GRAY));
        }
        for (Map.Entry<String, JsonElement> metadataEntry : metadata.additional().entrySet()) {
            appendMetadata(entry, metadataEntry.getKey(), metadataEntry.getValue(), 0);
        }
        return entry;
    }

    private static void appendMetadata(MutableComponent entry, String key, JsonElement value, int depth) {
        String indentation = "  " + "    ".repeat(depth);
        String bullet = depth == 0 ? "• " : "";
        if (value.isJsonObject()) {
            if (!key.isEmpty()) {
                entry.append(Component.literal("\n" + indentation + bullet + key).withStyle(ChatFormatting.DARK_GRAY));
            }
            if (value.getAsJsonObject().isEmpty()) {
                String emptyObject = key.isEmpty() ? "\n" + indentation + bullet + "{}" : " {}";
                entry.append(Component.literal(emptyObject).withStyle(ChatFormatting.GRAY));
                return;
            }
            int childDepth = key.isEmpty() ? depth : depth + 1;
            value.getAsJsonObject().entrySet().forEach(child -> appendMetadata(entry, child.getKey(), child.getValue(), childDepth));
            return;
        }
        if (value.isJsonArray()) {
            if (!key.isEmpty()) {
                entry.append(Component.literal("\n" + indentation + bullet + key).withStyle(ChatFormatting.DARK_GRAY));
            }
            if (value.getAsJsonArray().isEmpty()) {
                String emptyArray = key.isEmpty() ? "\n" + indentation + bullet + "[]" : " []";
                entry.append(Component.literal(emptyArray).withStyle(ChatFormatting.GRAY));
                return;
            }
            int childDepth = key.isEmpty() ? depth : depth + 1;
            value.getAsJsonArray().forEach(child -> appendMetadata(entry, "", child, childDepth));
            return;
        }

        String formattedValue = value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : value.toString();
        String label = key.isEmpty() ? "" : key + " ";
        entry.append(Component.literal("\n" + indentation + bullet + label).withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal(formattedValue).withStyle(ChatFormatting.GRAY));
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
            .append(Component.literal("  Duration: ").withStyle(ChatFormatting.GOLD))
            .append(Component.literal(String.format(Locale.ROOT, "%.1f s", report.elapsedSeconds())).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("\n\nServer performance").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("\n• TPS: ").withStyle(ChatFormatting.AQUA))
            .append(Component.literal(String.format(
                Locale.ROOT,
                "avg %.2f / min %.2f",
                report.averageTps(),
                report.minimumTps()
            )).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("\n• MSPT").withStyle(ChatFormatting.GREEN))
            .append(createStressStatistic("\n  ", "baseline", "%.2f ms", ChatFormatting.GRAY, report.baselineMspt()))
            .append(createStressStatistic("  ", "avg", "%.2f ms", ChatFormatting.GREEN, report.averageMspt()))
            .append(createStressStatistic("\n  ", "median", "%.2f ms", ChatFormatting.AQUA, report.medianMspt()))
            .append(createStressStatistic("  ", "p95", "%.2f ms", ChatFormatting.GOLD, report.percentile95Mspt()))
            .append(createStressStatistic("\n  ", "max", "%.2f ms", ChatFormatting.RED, report.maximumMspt()))
            .append(createStressStatistic("  ", "samples", "%.0f", ChatFormatting.LIGHT_PURPLE, report.measuredServerTicks()))
            .append(Component.literal("\n\nEmote processing").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("\n• Create: ").withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(String.format(
                Locale.ROOT,
                "%.2f ms",
                report.creationMillis()
            )).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("  Cleanup: ").withStyle(ChatFormatting.GOLD))
            .append(Component.literal(String.format(Locale.ROOT, "%.2f ms", report.cleanupMillis())).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("  Tick: ").withStyle(ChatFormatting.GREEN))
            .append(Component.literal(String.format(
                Locale.ROOT,
                "avg %.3f ms / max %.3f ms",
                report.averageManagerCpuMillis(),
                report.maximumManagerCpuMillis()
            )).withStyle(ChatFormatting.WHITE))
            .append(createPacketLoadSummary(report));
        source.sendSuccess(() -> message, true);
    }

    static Component createPacketLoadSummary(PlaybackStressTestReport report) {
        var packets = report.packetLoad();
        if (packets.packetFanout() == 0) {
            return Component.literal("\n\nPacket load\n• Fanout: disabled").withStyle(ChatFormatting.GRAY);
        }

        double elapsedSeconds = Math.max(report.elapsedSeconds(), 0.001D);
        double packetsPerSecond = packets.runtimePackets() / elapsedSeconds;
        double mebibytesPerSecond = packets.runtimeBytes() / 1_048_576.0D / elapsedSeconds;
        double averageEncodingMillis = packets.runtimeSamples() == 0
            ? 0.0D
            : packets.runtimeEncodingNanos() / 1_000_000.0D / packets.runtimeSamples();
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
            .append(Component.literal("\n• Runtime: ").withStyle(ChatFormatting.GREEN))
            .append(Component.literal(String.format(
                Locale.ROOT,
                "%,.0f packets/s / %.2f MiB/s",
                packetsPerSecond,
                mebibytesPerSecond
            )).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("\n• Encode: ").withStyle(ChatFormatting.GOLD))
            .append(Component.literal(String.format(
                Locale.ROOT,
                "avg %.3f ms / max %.3f ms",
                averageEncodingMillis,
                packets.maximumRuntimeEncodingNanosPerTick() / 1_000_000.0D
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
