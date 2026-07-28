package io.github.hanhy06.emote.server;

import io.github.hanhy06.emote.config.data.EmoteAccessConfig;
import io.github.hanhy06.emote.emote.PlayResult;
import io.github.hanhy06.emote.emote.PlayService;
import io.github.hanhy06.emote.permission.PermissionService;
import io.github.hanhy06.emote.playback.PlaybackManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

public final class IdleEmoteService {
    private static final long RETRY_INTERVAL_MILLIS = TimeUnit.SECONDS.toMillis(1);

    private final IdleEmoteResolver idleEmoteResolver;
    private final EmotePlayer emotePlayer;
    private final ActiveEmoteChecker activeEmoteChecker;
    private final LongSupplier clock;
    private final RandomGenerator random;
    private final Map<UUID, IdleState> playerStates = new HashMap<>();
    private final Map<UUID, String> lastPlayedEmotes = new HashMap<>();

    public IdleEmoteService(
        PermissionService permissionService,
        PlayService playService,
        PlaybackManager playbackManager
    ) {
        this(
            permissionService::findIdleEmote,
            playService::play,
            player -> playbackManager.findActiveEmote(player.getUUID()) != null,
            Util::getMillis,
            RandomGenerator.getDefault()
        );
    }

    IdleEmoteService(
        IdleEmoteResolver idleEmoteResolver,
        EmotePlayer emotePlayer,
        ActiveEmoteChecker activeEmoteChecker,
        LongSupplier clock,
        RandomGenerator random
    ) {
        this.idleEmoteResolver = Objects.requireNonNull(idleEmoteResolver, "idle emote resolver");
        this.emotePlayer = Objects.requireNonNull(emotePlayer, "emote player");
        this.activeEmoteChecker = Objects.requireNonNull(activeEmoteChecker, "active emote checker");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    public void tick(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            tickPlayer(player.getUUID(), player.getLastActionTime(), player);
        }
    }

    void tickPlayer(UUID playerUuid, long lastActionTime, ServerPlayer player) {
        Optional<EmoteAccessConfig.IdleEmote> resolvedIdle = this.idleEmoteResolver.find(player);
        if (lastActionTime <= 0L || resolvedIdle.isEmpty()) {
            this.playerStates.remove(playerUuid);
            return;
        }

        EmoteAccessConfig.IdleEmote idle = resolvedIdle.get();
        IdleState state = this.playerStates.get(playerUuid);
        if (state == null || state.lastActionTime() != lastActionTime || !state.idle().equals(idle)) {
            long firstAttemptTime = lastActionTime + TimeUnit.SECONDS.toMillis(idle.delaySeconds());
            String selectedEmote = selectEmote(playerUuid, idle.emote());
            state = new IdleState(lastActionTime, idle, selectedEmote, firstAttemptTime, false);
            this.playerStates.put(playerUuid, state);
        }

        long now = this.clock.getAsLong();
        if (state.played() || now < state.nextAttemptTime() || this.activeEmoteChecker.isActive(player)) {
            return;
        }

        PlayResult result = this.emotePlayer.play(player, state.selectedEmote());
        if (result.isSuccess()) {
            this.lastPlayedEmotes.put(playerUuid, state.selectedEmote());
            this.playerStates.put(playerUuid, new IdleState(
                lastActionTime,
                idle,
                state.selectedEmote(),
                state.nextAttemptTime(),
                true
            ));
        } else {
            this.playerStates.put(playerUuid, new IdleState(
                lastActionTime,
                idle,
                state.selectedEmote(),
                now + RETRY_INTERVAL_MILLIS,
                false
            ));
        }
    }

    private String selectEmote(UUID playerUuid, List<String> emotes) {
        if (emotes.size() == 1) {
            return emotes.getFirst();
        }

        int previousIndex = emotes.indexOf(this.lastPlayedEmotes.get(playerUuid));
        if (previousIndex < 0) {
            return emotes.get(this.random.nextInt(emotes.size()));
        }

        int selectedIndex = this.random.nextInt(emotes.size() - 1);
        if (selectedIndex >= previousIndex) {
            selectedIndex++;
        }
        return emotes.get(selectedIndex);
    }

    public void removePlayer(ServerPlayer player) {
        UUID playerUuid = player.getUUID();
        this.playerStates.remove(playerUuid);
        this.lastPlayedEmotes.remove(playerUuid);
    }

    public void clear() {
        this.playerStates.clear();
        this.lastPlayedEmotes.clear();
    }

    private record IdleState(
        long lastActionTime,
        EmoteAccessConfig.IdleEmote idle,
        String selectedEmote,
        long nextAttemptTime,
        boolean played
    ) {
    }

    @FunctionalInterface
    interface IdleEmoteResolver {
        Optional<EmoteAccessConfig.IdleEmote> find(ServerPlayer player);
    }

    @FunctionalInterface
    interface EmotePlayer {
        PlayResult play(ServerPlayer player, String id);
    }

    @FunctionalInterface
    interface ActiveEmoteChecker {
        boolean isActive(ServerPlayer player);
    }
}
