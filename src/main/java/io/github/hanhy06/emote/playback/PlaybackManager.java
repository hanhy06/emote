package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.api.PlayResult;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.config.Config;
import io.github.hanhy06.emote.config.ConfigListener;
import io.github.hanhy06.emote.emote.RegisteredEmote;
import io.github.hanhy06.emote.skin.PlayerSkinManager;
import io.github.hanhy06.emote.skin.PreparedPlayerSkin;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PlaybackManager implements ConfigListener {
    public static PlaybackManager INSTANCE;

    public static final int DEFAULT_LOAD_TEST_INSTANCE_COUNT = PlaybackLoadTest.DEFAULT_INSTANCE_COUNT;
    public static final int MAX_LOAD_TEST_INSTANCE_COUNT = PlaybackLoadTest.MAX_INSTANCE_COUNT;
    private static final double MOVE_STOP_HORIZONTAL_DISTANCE_SQUARED = 0.01D;
    private static final double MOVE_STOP_VERTICAL_DISTANCE = 0.12D;

    private final Map<UUID, ActiveEmote> activeEmoteMap = new ConcurrentHashMap<>();
    private final List<PlaybackStateListener> stateListeners = new ArrayList<>();

    private final PlayerSkinManager playerSkinManager;
    private final PlaybackEntityController entityController = new PlaybackEntityController();
    private final PlaybackLoadTest loadTest = new PlaybackLoadTest(this.entityController);
    private final PlayerVisibilityService playerVisibilityService;
    private int maxActiveDisplayEntities = Config.DEFAULT_MAX_ACTIVE_DISPLAY_ENTITIES;

    public PlaybackManager(PlayerSkinManager playerSkinManager) {
        INSTANCE = this;

        this.playerSkinManager = playerSkinManager;
        this.playerVisibilityService = new PlayerVisibilityService(this);
        this.playerSkinManager.addReadyListener(this::refreshPlayerSkin);
    }

    public void registerVisibilityService() {
        this.playerVisibilityService.register();
    }

    @Override
    public void onConfigReload(Config newConfig) {
        this.maxActiveDisplayEntities = newConfig.maxActiveDisplayEntities();
    }

    public void addStateListener(PlaybackStateListener stateListener) {
        this.stateListeners.add(Objects.requireNonNull(stateListener, "stateListener"));
    }

    public PlayResult startEmote(ServerPlayer player, RegisteredEmote emote) {
        int projectedDisplayEntities = projectedDisplayEntityCount(
            activeDisplayEntityCount(),
            displayEntityCount(this.activeEmoteMap.get(player.getUUID())),
            emote.displayNodeCount()
        );
        if (exceedsDisplayEntityLimit(projectedDisplayEntities, this.maxActiveDisplayEntities)) {
            return PlayResult.failure(
                "Active emote parts would exceed the server limit ("
                    + projectedDisplayEntities + "/" + this.maxActiveDisplayEntities + ")."
            );
        }

        PlayerSkinManager.SkinPreparation skinPreparation = this.playerSkinManager.preparePlayerSkin(
            player,
            emote.skinParts()
        );
        if (skinPreparation.preparing()) {
            return PlayResult.failure("Preparing player skin... " + skinPreparation.progressPercent() + "%");
        }
        PreparedPlayerSkin preparedSkin = skinPreparation.preparedPlayerSkin();

        stopEmote(player, PlaybackStopReason.REPLACED);
        PlaybackNodes nodes = null;
        try {
            nodes = this.entityController.create(player, emote);
            TimelinePlayer timeline = new TimelinePlayer(emote.playbackPlan(), nodes, this.entityController);
            if (emote.animation().timeline().loop() == EmoteAnimation.LoopMode.SERVER_SYNC) {
                timeline.startSynchronized(Emote.SERVER.overworld().getGameTime());
            } else {
                timeline.start();
            }
            this.entityController.add(player.level(), nodes);
            if (emote.animation().timeline().loop() == EmoteAnimation.LoopMode.SERVER_SYNC) {
                timeline.resumeSynchronizedInterpolation();
            }
            EventPlayer events = new EventPlayer(
                emote.playbackPlan(),
                new EventCommandExecutor(player, nodes, timeline)
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
            startEvents(timeline, events);
            for (PlaybackStateListener stateListener : this.stateListeners) {
                stateListener.onEmoteStarted(player, activeEmote);
            }
            return PlayResult.SUCCESS;
        } catch (RuntimeException exception) {
            Emote.LOGGER.warn("Failed to start emote {} for {}", emote.id(), player.getScoreboardName(), exception);
            ActiveEmote activeEmote = this.activeEmoteMap.remove(player.getUUID());
            if (activeEmote != null) {
                cleanupActiveEmote(activeEmote, false, PlaybackStopReason.ERROR, null);
            } else if (nodes != null) {
                this.entityController.remove(player.level(), nodes);
            }
            return PlayResult.failure("Failed to start emote.");
        }
    }

    private void refreshPlayerSkin(UUID playerUuid) {
        ActiveEmote activeEmote = this.activeEmoteMap.get(playerUuid);
        if (activeEmote == null) {
            return;
        }
        ServerPlayer player = Emote.SERVER.getPlayerList().getPlayer(playerUuid);
        if (player == null) {
            return;
        }
        PlayerSkinManager.SkinPreparation preparation = this.playerSkinManager.preparePlayerSkin(
            player,
            activeEmote.skinParts()
        );
        this.playerSkinManager.applySkinParts(
            activeEmote.nodes().nodes(),
            activeEmote.skinParts(),
            preparation.preparedPlayerSkin()
        );
    }

    public ActiveEmote stopEmote(ServerPlayer player) {
        return stopEmote(player, PlaybackStopReason.MANUAL);
    }

    public ActiveEmote stopEmote(ServerPlayer player, PlaybackStopReason reason) {
        return stopEmote(player.getUUID(), reason, player);
    }

    private void stopEmote(UUID playerUuid, PlaybackStopReason reason) {
        stopEmote(playerUuid, reason, null);
    }

    private ActiveEmote stopEmote(
        UUID playerUuid,
        PlaybackStopReason reason,
        @Nullable ServerPlayer knownPlayer
    ) {
        ActiveEmote activeEmote = this.activeEmoteMap.remove(playerUuid);
        if (activeEmote == null) {
            return activeEmote;
        }
        cleanupActiveEmote(activeEmote, true, reason, knownPlayer);
        return activeEmote;
    }

    public ActiveEmote findActiveEmote(UUID playerUuid) {
        return this.activeEmoteMap.get(playerUuid);
    }

    public void tick() {
        this.loadTest.tick();
        if (this.activeEmoteMap.isEmpty()) {
            return;
        }

        List<StopRequest> stopRequests = null;
        for (ActiveEmote activeEmote : this.activeEmoteMap.values()) {
            ServerPlayer player = Emote.SERVER.getPlayerList().getPlayer(activeEmote.playerUuid());
            PlaybackStopReason stopReason = null;
            if (!canKeepPlaying(player, activeEmote)) {
                stopReason = PlaybackStopReason.PLAYER_UNAVAILABLE;
            } else if (hasMovedDuringPlayback(player, activeEmote)) {
                stopReason = PlaybackStopReason.MOVED;
            } else {
                try {
                    this.entityController.updateViewRotation(activeEmote.nodes(), player.getYRot());
                    TimelinePlayer.AdvanceResult result = advanceTimeline(activeEmote.timeline(), activeEmote.events());
                    if (result == TimelinePlayer.AdvanceResult.FINISHED) {
                        stopReason = PlaybackStopReason.FINISHED;
                    } else {
                        this.playerVisibilityService.tick(player, activeEmote);
                    }
                } catch (RuntimeException exception) {
                    Emote.LOGGER.warn("Failed while playing emote {}", activeEmote.id(), exception);
                    stopReason = PlaybackStopReason.ERROR;
                }
            }
            if (stopReason != null) {
                if (stopRequests == null) {
                    stopRequests = new ArrayList<>();
                }
                stopRequests.add(new StopRequest(activeEmote.playerUuid(), stopReason));
            }
        }

        if (stopRequests != null) {
            for (StopRequest request : stopRequests) {
                stopEmote(request.playerUuid(), request.reason());
            }
        }
    }

    static void startEvents(TimelinePlayer timeline, EventPlayer events) {
        events.start();
        if (timeline.currentTick() == 0) {
            events.timelineTick(0);
        }
    }

    static TimelinePlayer.AdvanceResult advanceTimeline(TimelinePlayer timeline, EventPlayer events) {
        int previousTick = timeline.currentTick();
        TimelinePlayer.AdvanceResult result = timeline.advance();
        if (result != TimelinePlayer.AdvanceResult.RESTARTED && timeline.currentTick() != previousTick) {
            events.timelineTick(timeline.currentTick());
        }
        if (result == TimelinePlayer.AdvanceResult.LOOP_BOUNDARY) {
            events.loop();
            result = timeline.continueAfterLoopEvent();
        }
        if (result == TimelinePlayer.AdvanceResult.RESTARTED) {
            events.timelineTick(0);
        }
        return result;
    }

    public void stopAllEmotes() {
        stopAllEmotes(PlaybackStopReason.MANUAL);
    }

    public void stopAllEmotes(PlaybackStopReason reason) {
        this.loadTest.stop();
        for (UUID playerUuid : List.copyOf(this.activeEmoteMap.keySet())) {
            stopEmote(playerUuid, reason);
        }
    }

    public int startLoadTest(
        ServerLevel level,
        Vec3 origin,
        float yaw,
        List<RegisteredEmote> emotes,
        int instanceCount
    ) {
        return this.loadTest.start(level, origin, yaw, emotes, instanceCount);
    }

    public @Nullable PlaybackLoadTestReport stopLoadTest() {
        return this.loadTest.stop();
    }

    public void stopId(String id) {
        stopId(id, PlaybackStopReason.EMOTE_REMOVED);
    }

    public void stopId(String id, PlaybackStopReason reason) {
        this.loadTest.stopId(id);
        List<UUID> playerUuidList = this.activeEmoteMap.entrySet().stream()
            .filter(entry -> entry.getValue().id().equals(id))
            .map(Map.Entry::getKey)
            .toList();
        for (UUID playerUuid : playerUuidList) {
            stopEmote(playerUuid, reason);
        }
    }

    private void cleanupActiveEmote(
        ActiveEmote activeEmote,
        boolean notifyListeners,
        PlaybackStopReason reason,
        @Nullable ServerPlayer knownPlayer
    ) {
        try {
            activeEmote.events().stop();
        } catch (RuntimeException exception) {
            Emote.LOGGER.warn("Failed to run stop events for emote {}", activeEmote.id(), exception);
        } finally {
            ServerLevel level = Emote.SERVER.getLevel(activeEmote.levelKey());
            if (level != null) {
                this.entityController.remove(level, activeEmote.nodes());
            }
            ServerPlayer player = knownPlayer != null
                ? knownPlayer
                : Emote.SERVER.getPlayerList().getPlayer(activeEmote.playerUuid());
            if (player != null) {
                this.playerVisibilityService.stop(player, activeEmote);
                if (notifyListeners) {
                    for (PlaybackStateListener stateListener : this.stateListeners) {
                        stateListener.onEmoteStopped(player, activeEmote, reason);
                    }
                }
            }
        }
    }

    private boolean canKeepPlaying(ServerPlayer player, ActiveEmote activeEmote) {
        return player != null
            && player.isAlive()
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

    int activeDisplayEntityCount() {
        return this.loadTest.displayEntityCount() + this.activeEmoteMap.values().stream()
            .mapToInt(this::displayEntityCount)
            .sum();
    }

    static boolean exceedsDisplayEntityLimit(int projectedDisplayEntities, int limit) {
        return limit > 0 && projectedDisplayEntities > limit;
    }

    static int projectedDisplayEntityCount(int activeDisplayEntities, int replacedDisplayEntities, int requestedDisplayEntities) {
        return activeDisplayEntities - replacedDisplayEntities + requestedDisplayEntities;
    }

    private int displayEntityCount(@Nullable ActiveEmote activeEmote) {
        if (activeEmote == null) {
            return 0;
        }
        return (int) activeEmote.nodes().nodes().values().stream()
            .filter(node -> !node.isAnchor())
            .count();
    }

    private record StopRequest(UUID playerUuid, PlaybackStopReason reason) {
    }
}
