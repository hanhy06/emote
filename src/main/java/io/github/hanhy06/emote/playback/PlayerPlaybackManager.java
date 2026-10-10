package io.github.hanhy06.emote.playback;

import com.mojang.datafixers.util.Pair;
import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.PlayResult;
import io.github.hanhy06.emote.api.PlaybackPlacement;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.content.PreparedEmote;
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
import io.github.hanhy06.emote.content.PreparedAnimation;

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
    public int activePlayerCount() { return this.playerSessions.size(); }

    public PlayResult start(ServerPlayer player, PreparedEmote emote) {
        return start(player, emote, PlaybackPlacement.actor());
    }

    public PlayResult start(ServerPlayer player, PreparedEmote emote, PlaybackPlacement placement) {
        if (this.closingPlayers.contains(player.getUUID())) return PlayResult.failure("Your previous emote is still closing.");
        PlayerSkinPreparation preparation = this.skins.preparePlayerSkin(player, emote.skinBindings());
        if (preparation.preparing()) return PlayResult.failure("Preparing your skin… " + preparation.progressPercent() + "%");
        RootTransform root = placement.mode() == PlaybackPlacement.Mode.EXTERNAL
            ? RootTransform.create(placement.position(), placement.yaw())
            : RootTransform.create(player.position(), player.getYRot());
        PlayerPlaybackState previousState = playerState(player.getUUID());
        boolean wasInvisible = previousState != null && previousState.behavior().hidden() ? previousState.wasInvisible() : player.isInvisible();
        PlayerPlaybackState playerState = new PlayerPlaybackState(player.position(), emote.skinBindings(), wasInvisible, emote.playerBehavior());
        List<PlaybackStateListener> playbackListeners = List.copyOf(this.listeners);
        PlaybackEngine.Lifecycle lifecycle = new PlaybackEngine.Lifecycle() {
            private int notifiedListeners;
            @Override public void onStarted(PlaybackSession session) {
                playerSessions.put(player.getUUID(), new PlayerPlayback(session, playerState));
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
            @Override public RootTransform resolveActorPlacement(PlaybackSession session) {
                Vec3 position = playerState.behavior().stopConditions().movementDistance() == 0
                    ? player.position() : playerState.startPosition();
                return RootTransform.create(position, player.getYRot());
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
        var result = this.engine.start(new PlaybackEngine.Request(player.level(), root, emote,
            Map.of("actor", player), PlayerMolangQueries.forPlayer(player), player.createCommandSourceStack(),
            preparation.textures(), lifecycle, placement.mode()), findActive(player.getUUID()));
        return switch (result) {
            case PlaybackEngine.StartResult.Success success -> new PlayResult.Success(success.session().info());
            case PlaybackEngine.StartResult.Failure failure -> PlayResult.failure(failure.message());
        };
    }

    public @Nullable PlaybackSession stop(ServerPlayer player) { return stop(player, PlaybackStopReason.MANUAL); }
    public @Nullable PlaybackSession stop(ServerPlayer player, PlaybackStopReason reason) {
        PlaybackSession session = findActive(player.getUUID());
        return session == null ? null : this.engine.stop(session, reason);
    }
    void updatePlayerPlacement(PlaybackSession session, Vec3 playerPosition, float playerYaw, EmotePlayerBehavior behavior) {
        if (session.placement().mode() != PlaybackPlacement.Mode.ACTOR) return;
        if (behavior.stopConditions().movementDistance() == 0) this.engine.entities().moveSceneTo(session.nodes(), playerPosition);
        this.engine.entities().updateViewRotation(session.nodes(), playerYaw, session.playback().rotationDeadzone());
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
        this.engine.entities().applySkin(session.nodes(), bindings, preparation.textures());
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

    private record PlayerPlayback(PlaybackSession session, PlayerPlaybackState playerState) {}

}
