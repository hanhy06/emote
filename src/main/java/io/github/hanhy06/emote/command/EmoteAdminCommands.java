package io.github.hanhy06.emote.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.emote.EmoteRegistry;
import io.github.hanhy06.emote.emote.RegisteredEmote;
import io.github.hanhy06.emote.permission.PermissionService;
import io.github.hanhy06.emote.playback.PlaybackManager;
import io.github.hanhy06.emote.server.EmoteReloadResult;
import io.github.hanhy06.emote.server.EmoteReloadService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;

import java.util.List;

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

    LiteralArgumentBuilder<CommandSourceStack> createStopAllCommand() {
        return Commands.literal("stop-all")
            .requires(this.permissionService.requireGameMaster())
            .executes(context -> stopAllEmotes(context.getSource()));
    }

    LiteralArgumentBuilder<CommandSourceStack> createEnableCommand() {
        return Commands.literal("enable")
            .requires(this.permissionService.requireGameMaster())
            .then(Commands.argument("id", IdentifierArgument.id())
                .suggests((ignoredContext, builder) -> SharedSuggestionProvider.suggest(getDisabledIds(), builder))
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
                    this.emoteRegistry.getAll().stream().map(RegisteredEmote::id),
                    builder
                ))
                .executes(context -> setEmoteEnabled(
                    context.getSource(),
                    IdentifierArgument.getId(context, "id").toString(),
                    false
                )));
    }

    private int reloadEmotes(CommandSourceStack source) {
        if (Emote.SERVER == null) {
            source.sendFailure(Component.literal("Server unavailable."));
            return 0;
        }
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

    private int stopAllEmotes(CommandSourceStack source) {
        this.playbackManager.stopAllEmotes();
        source.sendSuccess(() -> Component.literal("Stopped all emotes."), true);
        return 1;
    }

    private int setEmoteEnabled(CommandSourceStack source, String id, boolean enabled) {
        if (enabled) {
            if (this.configManager.getEmoteAccessConfig().isEnabled(id)) {
                source.sendFailure(Component.literal("Emote is not disabled: " + id));
                return 0;
            }
        } else if (this.emoteRegistry.find(id) == null) {
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

    private List<String> getDisabledIds() {
        return this.configManager.getEmoteAccessConfig().disabled();
    }
}
