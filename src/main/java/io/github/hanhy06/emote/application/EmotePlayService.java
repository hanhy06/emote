package io.github.hanhy06.emote.application;

import io.github.hanhy06.emote.api.PlayResult;
import io.github.hanhy06.emote.api.PlaySource;
import io.github.hanhy06.emote.content.EmoteCatalog;
import io.github.hanhy06.emote.content.PreparedDefinition;
import io.github.hanhy06.emote.permission.PermissionService;
import io.github.hanhy06.emote.playback.PlaybackEngine;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToLongFunction;

public class EmotePlayService {
    private final EmoteCatalog emoteCatalog;
    private final PlayPermissionChecker playPermissionChecker;
    private final BypassChecker bypassChecker;
    private final PlaybackStarter emoteStarter;
    private final PlayEventDispatcher eventDispatcher;
    private final Function<ServerPlayer, UUID> playerIdResolver;
    private final ToLongFunction<ServerPlayer> tickSource;
    private final EmoteCooldowns cooldowns = new EmoteCooldowns();

    public EmotePlayService(
        EmoteCatalog emoteCatalog,
        PermissionService permissionService,
        PlaybackEngine playbackEngine,
        ApiEventDispatcher apiEvents
    ) {
        this(
            emoteCatalog,
            (player, emote) -> permissionService.canPlay(player, emote.id()),
            permissionService::canBypass,
            playbackEngine::start,
            apiEvents::beforePlay,
            ServerPlayer::getUUID,
            player -> player.level().getGameTime()
        );
    }

    EmotePlayService(
        EmoteCatalog emoteCatalog,
        PlayPermissionChecker playPermissionChecker,
        PlaybackStarter emoteStarter,
        PlayEventDispatcher eventDispatcher
    ) {
        this(
            emoteCatalog,
            playPermissionChecker,
            ignored -> false,
            emoteStarter,
            eventDispatcher,
            ignored -> new UUID(0L, 0L),
            ignored -> 0L
        );
    }

    EmotePlayService(
        EmoteCatalog emoteCatalog,
        PlayPermissionChecker playPermissionChecker,
        BypassChecker bypassChecker,
        PlaybackStarter emoteStarter,
        PlayEventDispatcher eventDispatcher,
        Function<ServerPlayer, UUID> playerIdResolver,
        ToLongFunction<ServerPlayer> tickSource
    ) {
        this.emoteCatalog = emoteCatalog;
        this.playPermissionChecker = playPermissionChecker;
        this.bypassChecker = bypassChecker;
        this.emoteStarter = emoteStarter;
        this.eventDispatcher = eventDispatcher;
        this.playerIdResolver = playerIdResolver;
        this.tickSource = tickSource;
    }

    public PlayResult play(ServerPlayer player, String id) {
        return play(player, id, PlaySource.COMMAND);
    }

    public PlayResult play(ServerPlayer player, String id, PlaySource source) {
        PreparedDefinition emote = this.emoteCatalog.findDefinition(id);
        if (emote == null) {
            return PlayResult.failure("Unknown: " + id);
        }
        if (!emote.standalone()) {
            return PlayResult.failure("Sequence-only animation: " + id);
        }
        boolean bypass = this.bypassChecker.canBypass(player);
        if (!bypass && !this.playPermissionChecker.canPlay(player, emote)) {
            return PlayResult.failure("No emote permission.");
        }
        UUID playerId = null;
        long currentTick = 0L;
        if (!bypass && emote.cooldownTicks() > 0) {
            playerId = this.playerIdResolver.apply(player);
            currentTick = this.tickSource.applyAsLong(player);
            long remainingTicks = this.cooldowns.remainingTicks(playerId, emote.id(), currentTick);
            if (remainingTicks > 0L) {
                return PlayResult.failure("Emote cooldown: " + remainingTicks + "t remaining.");
            }
        }
        Component cancellationMessage = this.eventDispatcher.beforePlay(player, emote, source);
        if (cancellationMessage != null) {
            return PlayResult.failure(cancellationMessage);
        }
        PlayResult result = this.emoteStarter.start(player, emote);
        if (result.isSuccess() && !bypass && emote.cooldownTicks() > 0) {
            this.cooldowns.start(playerId, emote.id(), currentTick, emote.cooldownTicks());
        }
        return result;
    }

    public void clearCooldowns() {
        this.cooldowns.clear();
    }

    @FunctionalInterface
    interface PlayPermissionChecker {
        boolean canPlay(ServerPlayer player, PreparedDefinition emote);
    }

    @FunctionalInterface
    interface BypassChecker {
        boolean canBypass(ServerPlayer player);
    }

    @FunctionalInterface
    interface PlaybackStarter {
        PlayResult start(ServerPlayer player, PreparedDefinition emote);
    }

    @FunctionalInterface
    interface PlayEventDispatcher {
        Component beforePlay(ServerPlayer player, PreparedDefinition emote, PlaySource source);
    }
}
