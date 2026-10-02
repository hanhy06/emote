package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.*;
import io.github.hanhy06.emote.content.*;
import io.github.hanhy06.emote.playback.molang.PlayerMolangQueries;
import io.github.hanhy06.emote.playback.runtime.RootTransform;
import io.github.hanhy06.emote.playback.session.*;
import io.github.hanhy06.emote.skin.*;
import io.github.hanhy06.emote.skin.model.PlayerSkinPreparation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import java.util.*;
import java.util.random.RandomGenerator;

public final class PlayerPlaybackManager {
    private final PlaybackEngine engine;
    private final PlayerSkinManager skins;
    private final PlayerVisibilityService visibility;
    private final List<PlaybackStateListener> listeners = new ArrayList<>();
    private final Set<UUID> closingPlayers = new HashSet<>();
    private final Map<UUID, PlayerPlayback> playerSessions = new HashMap<>();
    private final RandomGenerator random = RandomGenerator.getDefault();

    public PlayerPlaybackManager(PlaybackEngine engine, PlayerSkinManager skins) {
        this.engine = engine;
        this.skins = skins;
        this.visibility = new PlayerVisibilityService(this);
        skins.addReadyListener(this::refreshPlayerSkin);
    }
    public PlaybackEngine engine() { return this.engine; }
    public void registerVisibilityService() { this.visibility.register(); }
    public void addStateListener(PlaybackStateListener listener) { this.listeners.add(Objects.requireNonNull(listener)); }
    public @Nullable PlaybackSession findActive(UUID playerId) {
        PlayerPlayback playback = this.playerSessions.get(playerId);
        return playback == null ? null : playback.session();
    }
    public @Nullable PlayerPlaybackState playerState(UUID playerId) {
        PlayerPlayback playback = this.playerSessions.get(playerId);
        return playback == null ? null : playback.participant();
    }
    public Optional<PlaybackInfo> playbackInfo(UUID sessionId) {
        return this.playerSessions.values().stream().filter(playback -> playback.session().sessionId().equals(sessionId))
            .findFirst().map(playback -> playback.session().playbackInfo(playback.participant().playerUuid()));
    }
    public int activeParticipantCount() { return this.playerSessions.size(); }

    public PlayResult start(ServerPlayer player, PlayableEmote definition) {
        if (this.closingPlayers.contains(player.getUUID())) return PlayResult.failure("Your previous emote is still closing.");
        PreparedAnimation animation = switch (definition) {
            case PreparedAnimation prepared -> prepared;
            case PreparedSequence sequence -> sequence.compile(this.random);
        };
        PlayerSkinPreparation preparation = this.skins.preparePlayerSkin(player, animation.skinBindings());
        if (preparation.preparing()) return PlayResult.failure("Preparing your skin… " + preparation.progressPercent() + "%");
        RootTransform root = RootTransform.create(player.position(), player.getYRot());
        PlayerPlaybackState previousState = playerState(player.getUUID());
        boolean wasInvisible = previousState != null && previousState.behavior().hidden() ? previousState.wasInvisible() : player.isInvisible();
        PlayerPlaybackState participant = new PlayerPlaybackState(player.getUUID(), root.position(), animation.skinBindings(), wasInvisible, definition.playerBehavior());
        List<PlaybackStateListener> playbackListeners = List.copyOf(this.listeners);
        PlaybackEngine.Lifecycle lifecycle = new PlaybackEngine.Lifecycle() {
            private int notifiedListeners;
            @Override public void onStarted(PlaybackSession session) {
                playerSessions.put(player.getUUID(), new PlayerPlayback(session, participant));
                visibility.start(player, participant);
                for (PlaybackStateListener listener : playbackListeners) {
                    this.notifiedListeners++;
                    listener.onStarted(player, session, participant);
                    if (!engine.contains(session)) break;
                }
            }
            @Override public @Nullable PlaybackStopReason beforeTick(PlaybackSession session) {
                if (!player.isAlive() || EmoteMod.SERVER.getPlayerList().getPlayer(player.getUUID()) != player
                    || !player.level().dimension().equals(session.levelKey())) return PlaybackStopReason.PLAYER_UNAVAILABLE;
                if (participant.behavior().stopConditions().submerge() && player.isUnderWater()) return PlaybackStopReason.SUBMERGED;
                if (movementResult(player, participant) == MovementResult.IMMEDIATE_STOP) return PlaybackStopReason.MOVED;
                return null;
            }
            @Override public void prepareFrame(PlaybackSession session) {
                if (participant.behavior().stopConditions().movementDistance() == 0) engine.entities().moveSceneTo(session.nodes(), player.position());
                engine.entities().updateViewRotation(session.nodes(), player.getYRot(), session.animation().rotationDeadzone());
                visibility.tick(player, participant);
            }
            @Override public void onClosing(PlaybackSession session) {
                playerSessions.remove(player.getUUID());
                closingPlayers.add(player.getUUID());
            }
            @Override public void onStopped(PlaybackSession session, PlaybackStopReason reason) {
                try {
                    visibility.stop(player, participant);
                    for (int i = 0; i < this.notifiedListeners; i++) playbackListeners.get(i).onStopped(player, session, participant, reason);
                } finally { closingPlayers.remove(player.getUUID()); }
            }
        };
        var result = this.engine.start(new PlaybackEngine.Request(player.level(), root, animation, definition.id(),
            Map.of("actor", player), PlayerMolangQueries.forPlayer(player), player.createCommandSourceStack(),
            preparation.preparedPlayerSkin(), lifecycle), findActive(player.getUUID()));
        return switch (result) {
            case PlaybackEngine.StartResult.Success success -> new PlayResult.Success(success.session().playbackInfo(player.getUUID()));
            case PlaybackEngine.StartResult.Failure failure -> PlayResult.failure(failure.message());
        };
    }

    public @Nullable PlaybackSession stop(ServerPlayer player) { return stop(player, PlaybackStopReason.MANUAL); }
    public @Nullable PlaybackSession stop(ServerPlayer player, PlaybackStopReason reason) {
        PlaybackSession session = findActive(player.getUUID());
        return session == null ? null : this.engine.stop(session, reason);
    }
    public void interrupt(ServerPlayer player, PlaybackStopReason reason) {
        PlaybackSession session = findActive(player.getUUID());
        if (session != null && shouldStopFor(playerState(player.getUUID()).behavior().stopConditions(), reason)) stop(player, reason);
    }
    private void refreshPlayerSkin(UUID playerId) {
        PlaybackSession session = findActive(playerId);
        ServerPlayer player = EmoteMod.SERVER.getPlayerList().getPlayer(playerId);
        if (session == null || player == null) return;
        var bindings = playerState(playerId).skinBindings();
        var preparation = this.skins.preparePlayerSkin(player, bindings);
        this.engine.entities().applySkin(session.nodes(), bindings, preparation.preparedPlayerSkin());
    }

    private MovementResult movementResult(ServerPlayer player, PlayerPlaybackState participant) {
        double movementDistance = participant.behavior().stopConditions().movementDistance();
        if (movementDistance == 0.0D) {
            return MovementResult.NONE;
        }
        Vec3 currentPosition = player.position();
        Vec3 startPosition = participant.startPosition();
        double xDistance = currentPosition.x - startPosition.x;
        double zDistance = currentPosition.z - startPosition.z;
        double horizontalDistanceSquared = xDistance * xDistance + zDistance * zDistance;
        return movementResult(horizontalDistanceSquared, movementDistance);
    }
    static MovementResult movementResult(double horizontalDistanceSquared, double movementDistance) {
        if (movementDistance == 0.0D) {
            return MovementResult.NONE;
        }
        if (horizontalDistanceSquared > movementDistance * movementDistance) {
            return MovementResult.IMMEDIATE_STOP;
        }
        return MovementResult.NONE;
    }

    static boolean shouldStopFor(EmotePlayerBehavior.StopConditions conditions, PlaybackStopReason reason) {
        return switch (reason) {
            case JUMPED -> conditions.jump();
            case MOUNTED -> conditions.ride();
            case DAMAGED -> conditions.damage();
            case ATTACKED -> conditions.attack();
            case GAME_MODE_CHANGED -> conditions.gameModeChange();
            default -> false;
        };
    }

    public PlayerSkinPreparation prepareStressTestSkin(ServerPlayer player, List<PreparedAnimation> emotes) {
        List<SkinBinding> bindings = emotes.stream().flatMap(emote -> emote.skinBindings().stream()).distinct().toList();
        return this.skins.preparePlayerSkin(player, bindings);
    }

    private record PlayerPlayback(PlaybackSession session, PlayerPlaybackState participant) {}

    enum MovementResult { NONE, IMMEDIATE_STOP }
}
