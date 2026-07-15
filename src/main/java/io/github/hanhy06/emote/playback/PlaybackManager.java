package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.emote.RegisteredEmote;
import io.github.hanhy06.emote.emote.PlayResult;
import io.github.hanhy06.emote.mixin.EntitySharedFlagsAccessor;
import io.github.hanhy06.emote.playback.data.ActiveEmote;
import io.github.hanhy06.emote.skin.PlayerSkinManager;
import io.github.hanhy06.emote.skin.PlayerSkinPreparationResult;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PlaybackManager {
    private static final double MOVE_STOP_HORIZONTAL_DISTANCE_SQUARED = 0.01D;
    private static final double MOVE_STOP_VERTICAL_DISTANCE = 0.12D;

    private final Map<UUID, ActiveEmote> activeEmoteMap = new ConcurrentHashMap<>();
    private final List<PlaybackStateListener> stateListeners = new ArrayList<>();
    private final PlayerSkinManager playerSkinManager;
    private final JsonPlaybackEntityController entityController = new JsonPlaybackEntityController();

    public PlaybackManager(PlayerSkinManager playerSkinManager) {
        this.playerSkinManager = playerSkinManager;
    }

    public void addStateListener(PlaybackStateListener stateListener) {
        this.stateListeners.add(Objects.requireNonNull(stateListener, "stateListener"));
    }

    public PlayResult startEmote(ServerPlayer player, RegisteredEmote emote) {
        MinecraftServer server = Emote.SERVER;
        if (server == null) {
            return PlayResult.failure("Server unavailable.");
        }
        if (player.isPassenger()) {
            return PlayResult.failure("Cannot play an emote while riding.");
        }

        PlayerSkinPreparationResult skinPreparation = this.playerSkinManager.preparePlayerSkin(
            player,
            emote.skinParts()
        );
        if (!skinPreparation.isReady()) {
            return PlayResult.failure(skinPreparation.errorMessage());
        }

        stopEmote(player);
        JsonPlaybackNodes nodes = null;
        try {
            nodes = this.entityController.spawn(player, emote.animation());
            JsonTimelinePlayer timeline = new JsonTimelinePlayer(emote.animation(), nodes, this.entityController);
            timeline.start();
            JsonEventPlayer events = new JsonEventPlayer(
                emote.animation(),
                new JsonEventCommandExecutor(server, player, nodes, timeline)
            );
            ActiveEmote activeEmote = new ActiveEmote(
                player.getUUID(),
                player.level().dimension(),
                emote.id(),
                player.position(),
                nodes,
                timeline,
                events,
                emote.hidePlayer(),
                player.isInvisible()
            );
            this.activeEmoteMap.put(player.getUUID(), activeEmote);
            if (activeEmote.playerVisibilityManaged()) {
                player.setInvisible(true);
            }
            this.playerSkinManager.applySkinParts(
                nodes.nodes(),
                emote.skinParts(),
                skinPreparation.preparedSkin(),
                emote.id()
            );
            events.start();
            for (PlaybackStateListener stateListener : this.stateListeners) {
                stateListener.onEmoteStarted(player, activeEmote);
            }
            return PlayResult.SUCCESS;
        } catch (RuntimeException exception) {
            Emote.LOGGER.warn("Failed to start emote {} for {}", emote.id(), player.getScoreboardName(), exception);
            ActiveEmote activeEmote = this.activeEmoteMap.remove(player.getUUID());
            if (activeEmote != null) {
                cleanupActiveEmote(server, activeEmote, false);
            } else if (nodes != null) {
                this.entityController.remove(player.level(), nodes);
            }
            return PlayResult.failure("Failed to start emote.");
        }
    }

    public ActiveEmote stopEmote(ServerPlayer player) {
        return stopEmote(player.getUUID());
    }

    private ActiveEmote stopEmote(UUID playerUuid) {
        ActiveEmote activeEmote = this.activeEmoteMap.remove(playerUuid);
        MinecraftServer server = Emote.SERVER;
        if (activeEmote == null || server == null) {
            return activeEmote;
        }
        cleanupActiveEmote(server, activeEmote, true);
        return activeEmote;
    }

    public ActiveEmote findActiveEmote(UUID playerUuid) {
        return this.activeEmoteMap.get(playerUuid);
    }

    public void tick() {
        MinecraftServer server = Emote.SERVER;
        if (server == null || this.activeEmoteMap.isEmpty()) {
            return;
        }

        List<UUID> playerUuidListToStop = new ArrayList<>();
        for (ActiveEmote activeEmote : this.activeEmoteMap.values()) {
            ServerPlayer player = server.getPlayerList().getPlayer(activeEmote.playerUuid());
            if (!canKeepPlaying(player, activeEmote) || hasMovedDuringPlayback(player, activeEmote)) {
                playerUuidListToStop.add(activeEmote.playerUuid());
                continue;
            }

            try {
                int previousTick = activeEmote.timeline().currentTick();
                JsonTimelinePlayer.AdvanceResult result = activeEmote.timeline().advance();
                if (activeEmote.timeline().currentTick() != previousTick) {
                    activeEmote.events().timelineTick(activeEmote.timeline().currentTick());
                }
                if (result == JsonTimelinePlayer.AdvanceResult.LOOP_BOUNDARY) {
                    activeEmote.events().loop();
                    result = activeEmote.timeline().continueAfterLoopEvent();
                }
                if (result == JsonTimelinePlayer.AdvanceResult.RESTARTED) {
                    activeEmote.events().timelineTick(0);
                } else if (result == JsonTimelinePlayer.AdvanceResult.FINISHED) {
                    playerUuidListToStop.add(activeEmote.playerUuid());
                    continue;
                }
                syncPlayerVisibility(player, activeEmote);
            } catch (RuntimeException exception) {
                Emote.LOGGER.warn("Failed while playing emote {}", activeEmote.id(), exception);
                playerUuidListToStop.add(activeEmote.playerUuid());
            }
        }

        for (UUID playerUuid : playerUuidListToStop) {
            stopEmote(playerUuid);
        }
    }

    public void stopAllEmotes() {
        for (UUID playerUuid : List.copyOf(this.activeEmoteMap.keySet())) {
            stopEmote(playerUuid);
        }
    }

    public void stopId(String id) {
        List<UUID> playerUuidList = this.activeEmoteMap.entrySet().stream()
            .filter(entry -> entry.getValue().id().equals(id))
            .map(Map.Entry::getKey)
            .toList();
        for (UUID playerUuid : playerUuidList) {
            stopEmote(playerUuid);
        }
    }

    private void cleanupActiveEmote(MinecraftServer server, ActiveEmote activeEmote, boolean notifyListeners) {
        try {
            activeEmote.events().stop();
        } catch (RuntimeException exception) {
            Emote.LOGGER.warn("Failed to run stop events for emote {}", activeEmote.id(), exception);
        } finally {
            ServerLevel level = server.getLevel(activeEmote.levelKey());
            if (level != null) {
                this.entityController.remove(level, activeEmote.nodes());
            }
            ServerPlayer player = server.getPlayerList().getPlayer(activeEmote.playerUuid());
            if (player != null) {
                if (activeEmote.playerVisibilityManaged()) {
                    player.setInvisible(activeEmote.wasInvisible());
                }
                if (notifyListeners) {
                    for (PlaybackStateListener stateListener : this.stateListeners) {
                        stateListener.onEmoteStopped(player, activeEmote);
                    }
                }
            }
        }
    }

    private void syncPlayerVisibility(ServerPlayer player, ActiveEmote activeEmote) {
        if (!activeEmote.playerVisibilityManaged()) {
            return;
        }
        player.setInvisible(true);
        EntityDataAccessor<Byte> sharedFlagsId = EntitySharedFlagsAccessor.emote$getSharedFlagsId();
        byte sharedFlags = player.getEntityData().get(sharedFlagsId);
        ClientboundSetEntityDataPacket visibilityPacket = new ClientboundSetEntityDataPacket(
            player.getId(),
            List.of(SynchedEntityData.DataValue.create(sharedFlagsId, sharedFlags))
        );
        player.level().getChunkSource().sendToTrackingPlayersAndSelf(player, visibilityPacket);
    }

    private boolean canKeepPlaying(ServerPlayer player, ActiveEmote activeEmote) {
        return player != null
            && player.isAlive()
            && !player.isPassenger()
            && player.level().dimension().equals(activeEmote.levelKey());
    }

    private boolean hasMovedDuringPlayback(ServerPlayer player, ActiveEmote activeEmote) {
        Vec3 currentPosition = player.position();
        Vec3 startPosition = activeEmote.startPosition();
        double xDistance = currentPosition.x - startPosition.x;
        double zDistance = currentPosition.z - startPosition.z;
        double horizontalDistanceSquared = xDistance * xDistance + zDistance * zDistance;
        double verticalDistance = Math.abs(currentPosition.y - startPosition.y);
        return horizontalDistanceSquared > MOVE_STOP_HORIZONTAL_DISTANCE_SQUARED
            || verticalDistance > MOVE_STOP_VERTICAL_DISTANCE;
    }
}
