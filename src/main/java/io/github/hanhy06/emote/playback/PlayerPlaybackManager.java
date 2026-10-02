package io.github.hanhy06.emote.playback;

import com.mojang.datafixers.util.Pair;
import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.PlayResult;
import io.github.hanhy06.emote.api.PlayOptions;
import io.github.hanhy06.emote.api.PlaybackPlacement;
import io.github.hanhy06.emote.api.PlaybackInfo;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.content.PlayableEmote;
import io.github.hanhy06.emote.content.PreparedAnimation;
import io.github.hanhy06.emote.content.PreparedSequence;
import io.github.hanhy06.emote.mixin.accessor.EntitySharedFlagsAccessor;
import io.github.hanhy06.emote.playback.molang.PlayerMolangQueries;
import io.github.hanhy06.emote.playback.runtime.RootTransform;
import io.github.hanhy06.emote.playback.session.PlaybackSession;
import io.github.hanhy06.emote.skin.PlayerSkinManager;
import io.github.hanhy06.emote.skin.SkinBinding;
import io.github.hanhy06.emote.skin.model.PlayerSkinPreparation;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.random.RandomGenerator;

public final class PlayerPlaybackManager {
    private static final List<EquipmentSlot> PLAYER_EQUIPMENT_SLOTS = List.of(
        EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.FEET,
        EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD
    );
    private static final List<Pair<EquipmentSlot, ItemStack>> EMPTY_EQUIPMENT = PLAYER_EQUIPMENT_SLOTS.stream()
        .map(slot -> Pair.of(slot, ItemStack.EMPTY)).toList();
    private final PlaybackEngine engine;
    private final PlayerSkinManager skins;
    private final List<PlaybackStateListener> listeners = new ArrayList<>();
    private final Set<UUID> closingPlayers = new HashSet<>();
    private final Map<UUID, PlayerPlayback> playerSessions = new HashMap<>();
    private final RandomGenerator random = RandomGenerator.getDefault();

    public PlayerPlaybackManager(PlaybackEngine engine, PlayerSkinManager skins) {
        this.engine = engine;
        this.skins = skins;
        skins.addReadyListener(this::refreshPlayerSkin);
    }
    public PlaybackEngine engine() { return this.engine; }
    public void register() {
        EntityTrackingEvents.START_TRACKING.register(this::handleStartTracking);
        PlaybackHooks.EQUIPMENT_SYNC.register(this::handleEquipmentSync);
    }
    public void addStateListener(PlaybackStateListener listener) { this.listeners.add(Objects.requireNonNull(listener)); }
    public @Nullable PlaybackSession findActive(UUID playerId) {
        PlayerPlayback playback = this.playerSessions.get(playerId);
        return playback == null ? null : playback.session();
    }
    public @Nullable PlayerPlaybackState playerState(UUID playerId) {
        PlayerPlayback playback = this.playerSessions.get(playerId);
        return playback == null ? null : playback.playerState();
    }
    public Optional<PlaybackInfo> playbackInfo(UUID sessionId) {
        return Optional.ofNullable(findPlayback(sessionId))
            .map(playback -> playback.session().playbackInfo(playback.playerState().playerUuid()));
    }
    public @Nullable PlaybackSession findSession(UUID sessionId) {
        PlayerPlayback playback = findPlayback(sessionId);
        return playback == null ? null : playback.session();
    }
    private @Nullable PlayerPlayback findPlayback(UUID sessionId) {
        return this.playerSessions.values().stream()
            .filter(playback -> playback.session().sessionId().equals(sessionId)).findFirst().orElse(null);
    }
    public int activePlayerCount() { return this.playerSessions.size(); }

    public PlayResult start(ServerPlayer player, PlayableEmote definition) {
        return start(player, definition, PlayOptions.createDefault());
    }

    public PlayResult start(ServerPlayer player, PlayableEmote definition, PlayOptions options) {
        if (this.closingPlayers.contains(player.getUUID())) return PlayResult.failure("Your previous emote is still closing.");
        PreparedAnimation animation = switch (definition) {
            case PreparedAnimation prepared -> prepared;
            case PreparedSequence sequence -> sequence.compile(this.random);
        };
        PlayerSkinPreparation preparation = this.skins.preparePlayerSkin(player, animation.skinBindings());
        if (preparation.preparing()) return PlayResult.failure("Preparing your skin… " + preparation.progressPercent() + "%");
        PlaybackPlacement placement = options.placement();
        RootTransform root = placement.mode() == PlaybackPlacement.Mode.EXTERNAL
            ? RootTransform.create(placement.position(), placement.yaw())
            : RootTransform.create(player.position(), player.getYRot());
        PlayerPlaybackState previousState = playerState(player.getUUID());
        boolean wasInvisible = previousState != null && previousState.behavior().hidden() ? previousState.wasInvisible() : player.isInvisible();
        PlayerPlaybackState playerState = new PlayerPlaybackState(player.getUUID(), player.position(), animation.skinBindings(), wasInvisible, definition.playerBehavior());
        List<PlaybackStateListener> playbackListeners = List.copyOf(this.listeners);
        PlaybackEngine.Lifecycle lifecycle = new PlaybackEngine.Lifecycle() {
            private int notifiedListeners;
            @Override public void onStarted(PlaybackSession session) {
                session.setPlacementMode(placement.mode());
                playerSessions.put(player.getUUID(), new PlayerPlayback(session, playerState, player));
                hidePlayer(player, playerState);
                for (PlaybackStateListener listener : playbackListeners) {
                    this.notifiedListeners++;
                    listener.onStarted(player, session, playerState);
                    if (!engine.contains(session)) break;
                }
            }
            @Override public @Nullable PlaybackStopReason beforeTick(PlaybackSession session) {
                if (!player.isAlive() || EmoteMod.SERVER.getPlayerList().getPlayer(player.getUUID()) != player
                    || !player.level().dimension().equals(session.levelKey())) return PlaybackStopReason.PLAYER_UNAVAILABLE;
                if (playerState.behavior().stopConditions().submerge() && player.isUnderWater()) return PlaybackStopReason.SUBMERGED;
                if (shouldStopForMovement(player, playerState)) return PlaybackStopReason.MOVED;
                return null;
            }
            @Override public void prepareFrame(PlaybackSession session) {
                updatePlayerPlacement(session, player.position(), player.getYRot(), playerState.behavior());
                if (playerState.behavior().hidden() && !player.isInvisible()) {
                    player.setInvisible(true);
                    syncPlayerVisibility(player);
                }
            }
            @Override public void onClosing(PlaybackSession session) {
                playerSessions.remove(player.getUUID());
                closingPlayers.add(player.getUUID());
            }
            @Override public void onStopped(PlaybackSession session, PlaybackStopReason reason) {
                try {
                    restorePlayerVisibility(player, playerState);
                    for (int i = 0; i < this.notifiedListeners; i++) playbackListeners.get(i).onStopped(player, session, playerState, reason);
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
    public @Nullable PlaybackSession stop(UUID sessionId, PlaybackStopReason reason) {
        PlaybackSession session = findSession(sessionId);
        return session == null ? null : this.engine.stop(session, reason);
    }

    public boolean setPlacement(UUID sessionId, PlaybackPlacement placement) {
        PlayerPlayback playback = findPlayback(sessionId);
        if (playback == null) return false;
        PlaybackSession session = playback.session();
        Vec3 position = placement.position();
        float yaw = placement.yaw();
        if (placement.mode() == PlaybackPlacement.Mode.PLAYER) {
            position = playback.playerState().behavior().stopConditions().movementDistance() == 0
                ? playback.player().position() : playback.playerState().startPosition();
            yaw = playback.player().getYRot();
        }
        this.engine.entities().moveSceneTo(session.nodes(), position);
        this.engine.entities().updateViewRotation(session.nodes(), yaw, 0);
        session.setPlacementMode(placement.mode());
        return true;
    }

    void updatePlayerPlacement(PlaybackSession session, Vec3 playerPosition, float playerYaw, EmotePlayerBehavior behavior) {
        if (session.placement().mode() != PlaybackPlacement.Mode.PLAYER) return;
        if (behavior.stopConditions().movementDistance() == 0) this.engine.entities().moveSceneTo(session.nodes(), playerPosition);
        this.engine.entities().updateViewRotation(session.nodes(), playerYaw, session.animation().rotationDeadzone());
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

    private static void hidePlayer(ServerPlayer player, PlayerPlaybackState playerState) {
        if (!playerState.behavior().hidden()) return;
        player.setInvisible(true);
        syncPlayerVisibility(player);
        sendToTrackingPlayers(player, EMPTY_EQUIPMENT);
    }

    private static void restorePlayerVisibility(ServerPlayer player, PlayerPlaybackState playerState) {
        if (!playerState.behavior().hidden()) return;
        player.setInvisible(playerState.wasInvisible());
        syncPlayerVisibility(player);
        sendToTrackingPlayers(player, createVisibleEquipment(player));
    }

    private void handleStartTracking(Entity entity, ServerPlayer trackingPlayer) {
        if (!(entity instanceof ServerPlayer emotePlayer)) return;
        PlayerPlaybackState playerState = playerState(emotePlayer.getUUID());
        if (playerState != null && playerState.behavior().hidden()) {
            trackingPlayer.connection.send(new ClientboundSetEquipmentPacket(emotePlayer.getId(), EMPTY_EQUIPMENT));
        }
    }

    private void handleEquipmentSync(ServerPlayer player, Map<EquipmentSlot, ItemStack> changedItems) {
        if (PLAYER_EQUIPMENT_SLOTS.stream().noneMatch(changedItems::containsKey)) return;
        PlayerPlaybackState playerState = playerState(player.getUUID());
        if (playerState != null && playerState.behavior().hidden()) sendToTrackingPlayers(player, EMPTY_EQUIPMENT);
    }

    private static void syncPlayerVisibility(ServerPlayer player) {
        EntityDataAccessor<Byte> sharedFlagsId = EntitySharedFlagsAccessor.emote$getSharedFlagsId();
        byte sharedFlags = player.getEntityData().get(sharedFlagsId);
        ClientboundSetEntityDataPacket packet = new ClientboundSetEntityDataPacket(
            player.getId(), List.of(SynchedEntityData.DataValue.create(sharedFlagsId, sharedFlags))
        );
        player.level().getChunkSource().sendToTrackingPlayersAndSelf(player, packet);
    }

    private static List<Pair<EquipmentSlot, ItemStack>> createVisibleEquipment(ServerPlayer player) {
        return PLAYER_EQUIPMENT_SLOTS.stream().map(slot -> Pair.of(slot, player.getItemBySlot(slot).copy())).toList();
    }

    private static void sendToTrackingPlayers(ServerPlayer player, List<Pair<EquipmentSlot, ItemStack>> equipment) {
        player.level().getChunkSource().sendToTrackingPlayers(player, new ClientboundSetEquipmentPacket(player.getId(), equipment));
    }

    private boolean shouldStopForMovement(ServerPlayer player, PlayerPlaybackState playerState) {
        double movementDistance = playerState.behavior().stopConditions().movementDistance();
        if (movementDistance == 0.0D) {
            return false;
        }
        Vec3 currentPosition = player.position();
        Vec3 startPosition = playerState.startPosition();
        double xDistance = currentPosition.x - startPosition.x;
        double zDistance = currentPosition.z - startPosition.z;
        double horizontalDistanceSquared = xDistance * xDistance + zDistance * zDistance;
        return shouldStopForMovement(horizontalDistanceSquared, movementDistance);
    }
    static boolean shouldStopForMovement(double horizontalDistanceSquared, double movementDistance) {
        return movementDistance != 0.0D && horizontalDistanceSquared > movementDistance * movementDistance;
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

    private record PlayerPlayback(PlaybackSession session, PlayerPlaybackState playerState, ServerPlayer player) {}

}
