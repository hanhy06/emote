package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.animation.EmoteAnimation;
import io.github.hanhy06.emote.emote.PlayResult;
import io.github.hanhy06.emote.emote.RegisteredEmote;
import io.github.hanhy06.emote.skin.PlayerSkinManager;
import io.github.hanhy06.emote.skin.PreparedPlayerSkin;
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
    private final PlaybackEntityController entityController = new PlaybackEntityController();
    private final PlayerVisibilityService playerVisibilityService;

    public PlaybackManager(PlayerSkinManager playerSkinManager) {
        this.playerSkinManager = playerSkinManager;
        this.playerVisibilityService = new PlayerVisibilityService(this);
        this.playerSkinManager.addReadyListener(this::refreshPlayerSkin);
    }

    public void registerVisibilityService() {
        this.playerVisibilityService.register();
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

        PreparedPlayerSkin preparedSkin = this.playerSkinManager.preparePlayerSkin(
            player,
            emote.skinParts()
        );
        if (!emote.skinParts().isEmpty()
            && (preparedSkin == null || !preparedSkin.containsAll(emote.skinParts()))) {
            return PlayResult.failure("Player skin is not ready.");
        }

        stopEmote(player);
        PlaybackNodes nodes = null;
        try {
            TimelinePlayer timeline;
            if (emote.animation().timeline().loop() == EmoteAnimation.LoopMode.SERVER_SYNC) {
                nodes = this.entityController.create(player, emote.animation());
                timeline = new TimelinePlayer(emote.animation(), nodes, this.entityController);
                timeline.startSynchronized(server.overworld().getGameTime());
                this.entityController.add(player.level(), nodes);
                timeline.resumeSynchronizedInterpolation();
            } else {
                nodes = this.entityController.spawn(player, emote.animation());
                timeline = new TimelinePlayer(emote.animation(), nodes, this.entityController);
                timeline.start();
            }
            EventPlayer events = new EventPlayer(
                emote.animation(),
                new EventCommandExecutor(server, player, nodes, timeline)
            );
            ActiveEmote activeEmote = new ActiveEmote(
                player.getUUID(),
                player.level().dimension(),
                emote.id(),
                player.position(),
                nodes,
                timeline,
                events,
                emote.skinParts(),
                emote.hidePlayer(),
                player.isInvisible()
            );
            this.activeEmoteMap.put(player.getUUID(), activeEmote);
            this.playerVisibilityService.start(player, activeEmote);
            this.playerSkinManager.applySkinParts(
                nodes.nodes(),
                emote.skinParts(),
                preparedSkin
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

    private void refreshPlayerSkin(UUID playerUuid) {
        MinecraftServer server = Emote.SERVER;
        ActiveEmote activeEmote = this.activeEmoteMap.get(playerUuid);
        if (server == null || activeEmote == null) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(playerUuid);
        if (player == null) {
            return;
        }
        PreparedPlayerSkin preparedSkin = this.playerSkinManager.preparePlayerSkin(player, activeEmote.skinParts());
        this.playerSkinManager.applySkinParts(activeEmote.nodes().nodes(), activeEmote.skinParts(), preparedSkin);
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
                TimelinePlayer.AdvanceResult result = activeEmote.timeline().advance();
                if (activeEmote.timeline().currentTick() != previousTick) {
                    activeEmote.events().timelineTick(activeEmote.timeline().currentTick());
                }
                if (result == TimelinePlayer.AdvanceResult.LOOP_BOUNDARY) {
                    activeEmote.events().loop();
                    result = activeEmote.timeline().continueAfterLoopEvent();
                }
                if (result == TimelinePlayer.AdvanceResult.RESTARTED) {
                    activeEmote.events().timelineTick(0);
                } else if (result == TimelinePlayer.AdvanceResult.FINISHED) {
                    playerUuidListToStop.add(activeEmote.playerUuid());
                    continue;
                }
                this.playerVisibilityService.tick(player, activeEmote);
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
                this.playerVisibilityService.stop(player, activeEmote);
                if (notifyListeners) {
                    for (PlaybackStateListener stateListener : this.stateListeners) {
                        stateListener.onEmoteStopped(player, activeEmote);
                    }
                }
            }
        }
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
