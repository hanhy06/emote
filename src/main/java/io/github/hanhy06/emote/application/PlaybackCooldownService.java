package io.github.hanhy06.emote.application;

import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.playback.PlaybackStateListener;
import io.github.hanhy06.emote.playback.session.PlaybackParticipant;
import io.github.hanhy06.emote.playback.session.PlaybackSession;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToLongFunction;

public final class PlaybackCooldownService implements PlaybackStateListener {
    private final Function<ServerPlayer, UUID> playerIdResolver;
    private final ToLongFunction<ServerPlayer> tickSource;
    private final Map<UUID, Map<String, CooldownState>> statesByPlayer = new HashMap<>();

    public PlaybackCooldownService() {
        this(ServerPlayer::getUUID, player -> player.level().getGameTime());
    }

    PlaybackCooldownService(
        Function<ServerPlayer, UUID> playerIdResolver,
        ToLongFunction<ServerPlayer> tickSource
    ) {
        this.playerIdResolver = Objects.requireNonNull(playerIdResolver, "player id resolver");
        this.tickSource = Objects.requireNonNull(tickSource, "tick source");
    }

    Status status(ServerPlayer player, String emoteId) {
        UUID playerId = this.playerIdResolver.apply(player);
        Map<String, CooldownState> playerStates = this.statesByPlayer.get(playerId);
        if (playerStates == null) {
            return Status.available();
        }
        CooldownState state = playerStates.get(emoteId);
        if (state instanceof InUse) {
            return Status.inUse();
        }
        if (!(state instanceof CoolingDown coolingDown)) {
            return Status.available();
        }

        long remainingTicks = coolingDown.readyTick() - this.tickSource.applyAsLong(player);
        if (remainingTicks > 0L) {
            return Status.coolingDown(remainingTicks);
        }
        removeState(playerId, emoteId, state);
        return Status.available();
    }

    Reservation reservation(ServerPlayer player, String emoteId, int durationTicks) {
        if (durationTicks <= 0) {
            throw new IllegalArgumentException("cooldown duration must be positive");
        }
        return new Reservation(this.playerIdResolver.apply(player), emoteId, durationTicks);
    }

    void claim(Reservation reservation) {
        Objects.requireNonNull(reservation, "reservation");
        this.statesByPlayer.computeIfAbsent(reservation.playerId(), ignored -> new HashMap<>())
            .put(reservation.emoteId(), new InUse(reservation.durationTicks()));
    }

    void release(Reservation reservation) {
        Objects.requireNonNull(reservation, "reservation");
        Map<String, CooldownState> playerStates = this.statesByPlayer.get(reservation.playerId());
        if (playerStates == null) {
            return;
        }
        CooldownState state = playerStates.get(reservation.emoteId());
        if (state instanceof InUse inUse && inUse.durationTicks() == reservation.durationTicks()) {
            removeState(reservation.playerId(), reservation.emoteId(), state);
        }
    }

    @Override
    public void onStarted(ServerPlayer player, PlaybackSession session, PlaybackParticipant participant) {
    }

    @Override
    public void onStopped(ServerPlayer player, PlaybackSession session, PlaybackParticipant participant, PlaybackStopReason reason) {
        onPlaybackEnded(player, session.id());
    }

    @Override
    public void onReservationReleased(UUID playerUuid, String emoteId) {
        Map<String, CooldownState> playerStates = this.statesByPlayer.get(playerUuid);
        if (playerStates == null) {
            return;
        }
        CooldownState state = playerStates.get(emoteId);
        if (state instanceof InUse) {
            removeState(playerUuid, emoteId, state);
        }
    }

    void onPlaybackEnded(ServerPlayer player, String emoteId) {
        UUID playerId = this.playerIdResolver.apply(player);
        Map<String, CooldownState> playerStates = this.statesByPlayer.get(playerId);
        if (playerStates == null) {
            return;
        }
        CooldownState state = playerStates.get(emoteId);
        if (state instanceof InUse inUse) {
            long readyTick = this.tickSource.applyAsLong(player) + inUse.durationTicks();
            playerStates.put(emoteId, new CoolingDown(readyTick));
        }
    }

    public void clear() {
        this.statesByPlayer.clear();
    }

    private void removeState(UUID playerId, String emoteId, CooldownState expectedState) {
        Map<String, CooldownState> playerStates = this.statesByPlayer.get(playerId);
        if (playerStates == null || !playerStates.remove(emoteId, expectedState)) {
            return;
        }
        if (playerStates.isEmpty()) {
            this.statesByPlayer.remove(playerId);
        }
    }

    record Reservation(UUID playerId, String emoteId, int durationTicks) {
        Reservation {
            Objects.requireNonNull(playerId, "player id");
            Objects.requireNonNull(emoteId, "emote id");
            if (durationTicks <= 0) {
                throw new IllegalArgumentException("cooldown duration must be positive");
            }
        }
    }

    record Status(State state, long remainingTicks) {
        private static Status available() {
            return new Status(State.AVAILABLE, 0L);
        }

        private static Status inUse() {
            return new Status(State.IN_USE, 0L);
        }

        private static Status coolingDown(long remainingTicks) {
            return new Status(State.COOLING_DOWN, remainingTicks);
        }
    }

    enum State {
        AVAILABLE,
        IN_USE,
        COOLING_DOWN
    }

    private sealed interface CooldownState permits InUse, CoolingDown {
    }

    private record InUse(int durationTicks) implements CooldownState {
    }

    private record CoolingDown(long readyTick) implements CooldownState {
    }
}
