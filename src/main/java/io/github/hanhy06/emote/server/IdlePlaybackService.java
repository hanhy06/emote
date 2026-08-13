package io.github.hanhy06.emote.server;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.api.PlayResult;
import io.github.hanhy06.emote.api.PlaySource;
import io.github.hanhy06.emote.config.AccessConfig;
import io.github.hanhy06.emote.selection.WeightedChoiceSelector;
import io.github.hanhy06.emote.config.AccessConfigListener;
import io.github.hanhy06.emote.emote.PlayService;
import io.github.hanhy06.emote.permission.PermissionService;
import io.github.hanhy06.emote.playback.PlaybackManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

public final class IdlePlaybackService implements AccessConfigListener {
    private static final long RETRY_INTERVAL_MILLIS = TimeUnit.SECONDS.toMillis(1);
    private static final long RESOLUTION_CACHE_MILLIS = TimeUnit.SECONDS.toMillis(1);

    private final IdleSettingsResolver idleEmoteResolver;
    private final PlaybackStarter playbackStarter;
    private final ActivePlaybackChecker activePlaybackChecker;
    private final LongSupplier clock;
    private final RandomGenerator random;
    private final Map<UUID, IdleState> playerStates = new HashMap<>();
    private final Map<UUID, String> lastPlayedEmotes = new HashMap<>();
    private final Map<UUID, IdleResolution> idleResolutions = new HashMap<>();

    public IdlePlaybackService(
        PermissionService permissionService,
        PlayService playService,
        PlaybackManager playbackManager
    ) {
        this(
            permissionService::findIdleSettings,
            (player, id) -> playService.play(player, id, PlaySource.IDLE),
            player -> playbackManager.findActive(player.getUUID()) != null,
            Util::getMillis,
            RandomGenerator.getDefault()
        );
    }

    IdlePlaybackService(
        IdleSettingsResolver idleEmoteResolver,
        PlaybackStarter playbackStarter,
        ActivePlaybackChecker activePlaybackChecker,
        LongSupplier clock,
        RandomGenerator random
    ) {
        this.idleEmoteResolver = Objects.requireNonNull(idleEmoteResolver, "idle emote resolver");
        this.playbackStarter = Objects.requireNonNull(playbackStarter, "playback starter");
        this.activePlaybackChecker = Objects.requireNonNull(activePlaybackChecker, "active playback checker");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    public void tick() {
        for (ServerPlayer player : Emote.SERVER.getPlayerList().getPlayers()) {
            tickPlayer(player.getUUID(), player.getLastActionTime(), player);
        }
    }

    void tickPlayer(UUID playerUuid, long lastActionTime, ServerPlayer player) {
        if (lastActionTime <= 0L) {
            this.playerStates.remove(playerUuid);
            this.idleResolutions.remove(playerUuid);
            return;
        }

        IdleState state = this.playerStates.get(playerUuid);
        long now = this.clock.getAsLong();
        Optional<AccessConfig.IdleSettings> resolvedIdle = resolveIdle(playerUuid, player, now);
        if (resolvedIdle.isEmpty()) {
            this.playerStates.remove(playerUuid);
            return;
        }

        AccessConfig.IdleSettings idle = resolvedIdle.get();
        if (state == null || state.lastActionTime() != lastActionTime || !state.idle().equals(idle)) {
            long firstAttemptTime = lastActionTime + ticksToMillis(idle.delayTicks());
            String selectedEmote = selectEmote(playerUuid, idle.choices());
            state = new IdleState(lastActionTime, idle, selectedEmote, firstAttemptTime);
            this.playerStates.put(playerUuid, state);
        }

        if (now < state.nextAttemptTime() || this.activePlaybackChecker.isActive(player)) {
            return;
        }

        PlayResult result = this.playbackStarter.play(player, state.selectedEmote());
        String selectedEmote = state.selectedEmote();
        long nextAttemptTime;
        if (result.isSuccess()) {
            this.lastPlayedEmotes.put(playerUuid, state.selectedEmote());
            selectedEmote = selectEmote(playerUuid, idle.choices());
            long intervalMillis = ticksToMillis(idle.delayTicks());
            long elapsedIntervals = (now - state.nextAttemptTime()) / intervalMillis + 1L;
            nextAttemptTime = state.nextAttemptTime() + elapsedIntervals * intervalMillis;
        } else {
            nextAttemptTime = now + RETRY_INTERVAL_MILLIS;
        }
        this.playerStates.put(playerUuid, new IdleState(
            lastActionTime,
            idle,
            selectedEmote,
            nextAttemptTime
        ));
    }

    @Override
    public void onAccessConfigReload(AccessConfig newConfig) {
        this.idleResolutions.clear();
    }

    private Optional<AccessConfig.IdleSettings> resolveIdle(
        UUID playerUuid,
        ServerPlayer player,
        long now
    ) {
        IdleResolution resolution = this.idleResolutions.get(playerUuid);
        if (resolution != null && now < resolution.expiresAt()) {
            return resolution.idle();
        }
        Optional<AccessConfig.IdleSettings> idle = this.idleEmoteResolver.find(player);
        this.idleResolutions.put(playerUuid, new IdleResolution(idle, now + RESOLUTION_CACHE_MILLIS));
        return idle;
    }

    private String selectEmote(UUID playerUuid, List<AccessConfig.IdleSettings.Choice> choices) {
        if (choices.size() == 1) {
            return choices.getFirst().id();
        }

        int previousIndex = -1;
        String previousId = this.lastPlayedEmotes.get(playerUuid);
        for (int index = 0; index < choices.size(); index++) {
            if (choices.get(index).id().equals(previousId)) {
                previousIndex = index;
                break;
            }
        }

        return choices.get(WeightedChoiceSelector.selectIndex(this.random, choices, AccessConfig.IdleSettings.Choice::chance, previousIndex)).id();
    }

    public void removePlayer(ServerPlayer player) {
        UUID playerUuid = player.getUUID();
        this.playerStates.remove(playerUuid);
        this.lastPlayedEmotes.remove(playerUuid);
        this.idleResolutions.remove(playerUuid);
    }

    public void clear() {
        this.playerStates.clear();
        this.lastPlayedEmotes.clear();
        this.idleResolutions.clear();
    }

    private static long ticksToMillis(int ticks) {
        return ticks * 50L;
    }

    private record IdleState(
        long lastActionTime,
        AccessConfig.IdleSettings idle,
        String selectedEmote,
        long nextAttemptTime
    ) {
    }

    private record IdleResolution(Optional<AccessConfig.IdleSettings> idle, long expiresAt) {
    }

    @FunctionalInterface
    interface IdleSettingsResolver {
        Optional<AccessConfig.IdleSettings> find(ServerPlayer player);
    }

    @FunctionalInterface
    interface PlaybackStarter {
        PlayResult play(ServerPlayer player, String id);
    }

    @FunctionalInterface
    interface ActivePlaybackChecker {
        boolean isActive(ServerPlayer player);
    }
}
