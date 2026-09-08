package io.github.hanhy06.emote.command;

import com.google.gson.JsonElement;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.content.EmoteCatalog;
import io.github.hanhy06.emote.content.PlayableEmote;
import io.github.hanhy06.emote.permission.PermissionService;
import io.github.hanhy06.emote.playback.PlaybackEngine;
import io.github.hanhy06.emote.server.ReloadResult;
import io.github.hanhy06.emote.server.ReloadService;
import io.github.hanhy06.emote.skin.PlayerSkinManager;
import io.github.hanhy06.emote.skin.SkinProcessingStats;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class AdminCommand {
    private final EmoteCatalog emoteCatalog;
    private final PlaybackEngine playbackEngine;
    private final PermissionService permissionService;
    private final ReloadService reloadService;
    private final ConfigManager configManager;
    private final PlayerSkinManager playerSkinManager;
    private final StressTestCommand stressTestCommand;

    public AdminCommand(
        EmoteCatalog emoteCatalog,
        PlaybackEngine playbackEngine,
        PermissionService permissionService,
        ReloadService reloadService,
        ConfigManager configManager,
        PlayerSkinManager playerSkinManager
    ) {
        this.emoteCatalog = emoteCatalog;
        this.playbackEngine = playbackEngine;
        this.permissionService = permissionService;
        this.reloadService = reloadService;
        this.configManager = configManager;
        this.playerSkinManager = playerSkinManager;
        this.stressTestCommand = new StressTestCommand(emoteCatalog, playbackEngine, permissionService);
    }

    void attachTo(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(createInfoCommand())
            .then(createListCommand())
            .then(createReloadCommand())
            .then(createStopPlayerCommand())
            .then(this.stressTestCommand.createCommand())
            .then(createEnableCommand())
            .then(createDisableCommand());
    }

    LiteralArgumentBuilder<CommandSourceStack> createInfoCommand() {
        return Commands.literal("info")
            .requires(this.permissionService.requireManage())
            .executes(context -> info(context.getSource()));
    }

    private int info(CommandSourceStack source) {
        SkinProcessingStats skinStats = this.playerSkinManager.processingStats();
        AdminInfoSnapshot snapshot = new AdminInfoSnapshot(
            this.playbackEngine.activeSessionCount(),
            this.playbackEngine.activeParticipantCount(),
            this.playbackEngine.activeDisplayEntityCount(),
            this.configManager.getConfig().maxActiveDisplayEntities(),
            skinStats,
            this.emoteCatalog.size(),
            this.configManager.getAccessConfig().disabled().size()
        );
        source.sendSuccess(() -> createInfoSummary(snapshot), false);
        return snapshot.activeSessions();
    }

    static Component createInfoSummary(AdminInfoSnapshot snapshot) {
        int displayLimit = snapshot.displayLimit();
        String displayUsage = displayLimit == 0
            ? snapshot.activeDisplays() + " / unlimited"
            : String.format(
                Locale.ROOT,
                "%d / %d (%.1f%%)",
                snapshot.activeDisplays(),
                displayLimit,
                snapshot.activeDisplays() * 100.0D / displayLimit
            );
        ChatFormatting displayColor = displayUsageColor(snapshot.activeDisplays(), displayLimit);
        SkinProcessingStats skin = snapshot.skin();

        return Component.literal("\n\n\n\n\nEmote server info").withStyle(ChatFormatting.GRAY)
            .append(Component.literal("\n\nPlayback").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("\n• Sessions: ").withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(Integer.toString(snapshot.activeSessions())).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("\n• Players: ").withStyle(ChatFormatting.GREEN))
            .append(Component.literal(Integer.toString(snapshot.activeParticipants())).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("\n• Displays: ").withStyle(displayColor))
            .append(Component.literal(displayUsage).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("\n\nSkin processing").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("\n• Provider: ").withStyle(ChatFormatting.AQUA))
            .append(Component.literal(skin.provider()).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("\n• Jobs: ").withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(skin.activeJobs() + " active · " + skin.queuedJobs() + " queued").withStyle(ChatFormatting.WHITE))
            .append(Component.literal("\n• Retries: ").withStyle(ChatFormatting.GOLD))
            .append(Component.literal(Integer.toString(skin.retryingJobs())).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("\n\nContent").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("\n• Emotes: ").withStyle(ChatFormatting.LIGHT_PURPLE))
            .append(Component.literal(snapshot.loadedEmotes() + " loaded · " + snapshot.disableRules() + " disable rules").withStyle(ChatFormatting.WHITE));
    }

    static ChatFormatting displayUsageColor(int activeDisplays, int displayLimit) {
        if (displayLimit == 0 || activeDisplays * 10L < displayLimit * 7L) return ChatFormatting.GREEN;
        if (activeDisplays * 10L < displayLimit * 9L) return ChatFormatting.YELLOW;
        return ChatFormatting.RED;
    }

    record AdminInfoSnapshot(
        int activeSessions,
        int activeParticipants,
        int activeDisplays,
        int displayLimit,
        SkinProcessingStats skin,
        int loadedEmotes,
        int disableRules
    ) {
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

    LiteralArgumentBuilder<CommandSourceStack> createStopPlayerCommand() {
        return Commands.literal("stop")
            .then(Commands.argument("player", EntityArgument.players())
                .requires(this.permissionService.requireManage())
                .executes(this::stopPlayer));
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

    private int stopPlayer(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        var players = EntityArgument.getPlayers(context, "player");
        int stoppedCount = 0;

        for (ServerPlayer player : players) {
            var session = this.playbackEngine.stop(player);
            if (session == null) {
                if (players.size() == 1) {
                    source.sendFailure(Component.literal(player.getName().getString() + " is not playing an emote."));
                }
                continue;
            }

            PlayableEmote emote = this.emoteCatalog.find(session.id());
            String displayName = emote == null ? session.id() : emote.name();
            source.sendSuccess(
                () -> Component.literal("Stopped " + displayName + " for " + player.getName().getString() + "."),
                true
            );
            stoppedCount++;
        }

        if (stoppedCount == 0 && players.size() > 1) {
            source.sendFailure(Component.literal("No selected players are playing an emote."));
        }
        return stoppedCount;
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
