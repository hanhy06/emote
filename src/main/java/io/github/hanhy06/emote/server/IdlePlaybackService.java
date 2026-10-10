package io.github.hanhy06.emote.server;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.PlayResult;
import io.github.hanhy06.emote.api.PlaySource;
import io.github.hanhy06.emote.application.EmotePlayService;
import io.github.hanhy06.emote.application.PlaybackPolicyService;
import io.github.hanhy06.emote.config.AccessConfig;
import io.github.hanhy06.emote.config.AccessConfigListener;
import io.github.hanhy06.emote.content.EmoteCatalog;
import io.github.hanhy06.emote.playback.PlayerPlaybackManager;
import io.github.hanhy06.emote.util.WeightedChoiceSelector;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;
import io.github.hanhy06.emote.content.PreparedEmote;

public final class IdlePlaybackService implements AccessConfigListener {
    static final int CHECK_INTERVAL_TICKS = 10;
    private static final long RETRY_INTERVAL_MILLIS = TimeUnit.SECONDS.toMillis(1);
    private static final long RESOLUTION_CACHE_MILLIS = TimeUnit.SECONDS.toMillis(1);

    private final PlaybackPolicyService playbackPolicy;
    private final EmotePlayService playService;
    private final PlayerPlaybackManager playerPlaybackManager;
    private final EmoteCatalog emoteCatalog;
    private final RandomGenerator random = RandomGenerator.getDefault();
    private final Map<UUID, IdleState> playerStates = new HashMap<>();
    private final Map<UUID, String> lastPlayedEmotes = new HashMap<>();
    private final Map<UUID, IdleResolution> idleResolutions = new HashMap<>();

    private int ticksUntilCheck;

    public IdlePlaybackService(
        PlaybackPolicyService playbackPolicy,
        EmotePlayService playService,
        PlayerPlaybackManager playerPlaybackManager,
        EmoteCatalog emoteCatalog
    ) {
        this.playbackPolicy = Objects.requireNonNull(playbackPolicy, "playback policy");
        this.playService = Objects.requireNonNull(playService, "play service");
        this.playerPlaybackManager = Objects.requireNonNull(playerPlaybackManager, "player playback manager");
        this.emoteCatalog = Objects.requireNonNull(emoteCatalog, "emote catalog");
    }

    public void tick() {
        if (!advanceCheckSchedule()) {
            return;
        }
        for (ServerPlayer player : EmoteMod.SERVER.getPlayerList().getPlayers()) {
            tickPlayer(player.getUUID(), player.getLastActionTime(), player);
        }
    }

    boolean advanceCheckSchedule() {
        if (this.ticksUntilCheck > 0) {
            this.ticksUntilCheck--;
            return false;
        }
        this.ticksUntilCheck = CHECK_INTERVAL_TICKS - 1;
        return true;
    }

    void tickPlayer(UUID playerUuid, long lastActionTime, ServerPlayer player) {
        if (lastActionTime <= 0L) {
            this.playerStates.remove(playerUuid);
            this.idleResolutions.remove(playerUuid);
            return;
        }

        IdleState state = this.playerStates.get(playerUuid);
        long now = Util.getMillis();
        Optional<AccessConfig.IdleSettings> resolvedIdle = resolveIdle(playerUuid, player, now);
        if (resolvedIdle.isEmpty()) {
            this.playerStates.remove(playerUuid);
            return;
        }

        AccessConfig.IdleSettings idle = resolvedIdle.get();
        if (state == null || state.lastActionTime() != lastActionTime || !state.idle().equals(idle)) {
            long firstAttemptTime = lastActionTime + ticksToMillis(idle.delayTicks());
            String selectedEmote = selectEmote(playerUuid, idle);
            state = new IdleState(lastActionTime, idle, selectedEmote, firstAttemptTime);
            this.playerStates.put(playerUuid, state);
        }

        if (now < state.nextAttemptTime() || this.playerPlaybackManager.findActive(player.getUUID()) != null) {
            return;
        }

        if (state.selectedEmote() == null) {
            this.playerStates.put(playerUuid, new IdleState(lastActionTime, idle, selectEmote(playerUuid, idle), now + RETRY_INTERVAL_MILLIS));
            return;
        }

        PlayResult result = this.playService.play(player, state.selectedEmote(), PlaySource.IDLE);
        String selectedEmote = state.selectedEmote();
        long nextAttemptTime;
        if (result.isSuccess()) {
            this.lastPlayedEmotes.put(playerUuid, state.selectedEmote());
            selectedEmote = selectEmote(playerUuid, idle);
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
        this.playerStates.clear();
        this.ticksUntilCheck = 0;
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
        Optional<AccessConfig.IdleSettings> idle = this.playbackPolicy.findIdleSettings(player);
        this.idleResolutions.put(playerUuid, new IdleResolution(idle, now + RESOLUTION_CACHE_MILLIS));
        return idle;
    }

    private String selectEmote(UUID playerUuid, AccessConfig.IdleSettings idle) {
        List<AccessConfig.IdleSettings.Choice> choices = idle.resolveChoices(this.emoteCatalog.emotes().stream().map(PreparedEmote::id).toList());
        if (choices.isEmpty()) {
            return null;
        }
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
        this.ticksUntilCheck = 0;
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

}
