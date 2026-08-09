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
    public static final int DEFAULT_LOAD_TEST_INSTANCE_COUNT = PlaybackLoadTest.DEFAULT_INSTANCE_COUNT;
    public static final int MAX_LOAD_TEST_INSTANCE_COUNT = PlaybackLoadTest.MAX_INSTANCE_COUNT;
    private final Map<UUID, ActivePlayback> activeEmoteMap = new ConcurrentHashMap<>();
    private final List<PlaybackStateListener> stateListeners = new ArrayList<>();

    private final PlayerSkinManager playerSkinManager;
    private final PlaybackEntityController entityController = new PlaybackEntityController();
    private final PlaybackLoadTest loadTest = new PlaybackLoadTest(this.entityController);
    private final PlayerVisibilityService playerVisibilityService;
    private int maxActiveDisplayEntities = Config.DEFAULT_MAX_ACTIVE_DISPLAY_ENTITIES;

    public PlaybackManager(PlayerSkinManager playerSkinManager) {
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

    public PlayResult start(ServerPlayer player, RegisteredEmote emote) {
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

        stop(player, PlaybackStopReason.REPLACED);
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
            ActivePlayback activeEmote = new ActivePlayback(
                player.getUUID(),
                player.level().dimension(),
                emote.id(),
                player.position(),
                nodes,
                timeline,
                events,
                emote.skinParts(),
                emote.playerBehavior(),
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
                stateListener.onStarted(player, activeEmote);
            }
            return PlayResult.SUCCESS;
        } catch (RuntimeException exception) {
            Emote.LOGGER.warn("Failed to start emote {} for {}", emote.id(), player.getScoreboardName(), exception);
            ActivePlayback activeEmote = this.activeEmoteMap.remove(player.getUUID());
            if (activeEmote != null) {
                cleanupActive(activeEmote, false, PlaybackStopReason.ERROR, null);
            } else if (nodes != null) {
                this.entityController.remove(player.level(), nodes);
            }
            return PlayResult.failure("Failed to start emote.");
        }
    }

    private void refreshPlayerSkin(UUID playerUuid) {
        ActivePlayback activeEmote = this.activeEmoteMap.get(playerUuid);
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

    public ActivePlayback stop(ServerPlayer player) {
        return stop(player, PlaybackStopReason.MANUAL);
    }

    public ActivePlayback stop(ServerPlayer player, PlaybackStopReason reason) {
        return stop(player.getUUID(), reason, player);
    }

    public void interrupt(ServerPlayer player, PlaybackStopReason reason) {
        ActivePlayback activeEmote = this.activeEmoteMap.get(player.getUUID());
        if (activeEmote == null || !shouldStopFor(activeEmote.playerBehavior().stopConditions(), reason)) {
            return;
        }
        stop(player, reason);
    }

    private void stop(UUID playerUuid, PlaybackStopReason reason) {
        stop(playerUuid, reason, null);
    }

    private ActivePlayback stop(
        UUID playerUuid,
        PlaybackStopReason reason,
        @Nullable ServerPlayer knownPlayer
    ) {
        ActivePlayback activeEmote = this.activeEmoteMap.remove(playerUuid);
        if (activeEmote == null) {
            return null;
        }
        cleanupActive(activeEmote, true, reason, knownPlayer);
        return activeEmote;
    }

    public ActivePlayback findActive(UUID playerUuid) {
        return this.activeEmoteMap.get(playerUuid);
    }

    public void tick() {
        this.loadTest.tick();
        if (this.activeEmoteMap.isEmpty()) {
            return;
        }

        List<StopRequest> stopRequests = null;
        for (ActivePlayback activeEmote : this.activeEmoteMap.values()) {
            ServerPlayer player = Emote.SERVER.getPlayerList().getPlayer(activeEmote.playerUuid());
            PlaybackStopReason stopReason = null;
            if (!canKeepPlaying(player, activeEmote)) {
                stopReason = PlaybackStopReason.PLAYER_UNAVAILABLE;
            } else if (activeEmote.playerBehavior().stopConditions().submerge() && player.isUnderWater()) {
                stopReason = PlaybackStopReason.SUBMERGED;
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
                stop(request.playerUuid(), request.reason());
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

    public void stopAll() {
        stopAll(PlaybackStopReason.MANUAL);
    }

    public void stopAll(PlaybackStopReason reason) {
        this.loadTest.stop();
        for (UUID playerUuid : List.copyOf(this.activeEmoteMap.keySet())) {
            stop(playerUuid, reason);
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

    public void stopById(String id) {
        stopById(id, PlaybackStopReason.EMOTE_REMOVED);
    }

    public void stopById(String id, PlaybackStopReason reason) {
        this.loadTest.stopById(id);
        List<UUID> playerUuidList = this.activeEmoteMap.entrySet().stream()
            .filter(entry -> entry.getValue().id().equals(id))
            .map(Map.Entry::getKey)
            .toList();
        for (UUID playerUuid : playerUuidList) {
            stop(playerUuid, reason);
        }
    }

    private void cleanupActive(
        ActivePlayback activeEmote,
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
                        stateListener.onStopped(player, activeEmote, reason);
                    }
                }
            }
        }
    }

    private boolean canKeepPlaying(ServerPlayer player, ActivePlayback activeEmote) {
        return player != null
            && player.isAlive()
            && player.level().dimension().equals(activeEmote.levelKey());
    }

    private boolean hasMovedDuringPlayback(ServerPlayer player, ActivePlayback activeEmote) {
        double movementDistance = activeEmote.playerBehavior().stopConditions().movementDistance();
        if (movementDistance == 0.0D) {
            return false;
        }
        Vec3 currentPosition = player.position();
        Vec3 startPosition = activeEmote.startPosition();
        double xDistance = currentPosition.x - startPosition.x;
        double zDistance = currentPosition.z - startPosition.z;
        double horizontalDistanceSquared = xDistance * xDistance + zDistance * zDistance;
        return horizontalDistanceSquared > movementDistance * movementDistance;
    }

    static boolean shouldStopFor(EmoteAnimation.StopConditions conditions, PlaybackStopReason reason) {
        return switch (reason) {
            case JUMPED -> conditions.jump();
            case MOUNTED -> conditions.ride();
            case DAMAGED -> conditions.damage();
            case ATTACKED -> conditions.attack();
            case GAME_MODE_CHANGED -> conditions.gameModeChange();
            default -> false;
        };
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

    private int displayEntityCount(@Nullable ActivePlayback activeEmote) {
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
