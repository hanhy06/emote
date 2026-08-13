package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.PlayResult;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.config.Config;
import io.github.hanhy06.emote.config.ConfigListener;
import io.github.hanhy06.emote.emote.EmoteDefinition;
import io.github.hanhy06.emote.emote.RegisteredEmote;
import io.github.hanhy06.emote.emote.RegisteredSequence;
import io.github.hanhy06.emote.skin.PlayerSkinManager;
import io.github.hanhy06.emote.skin.model.PreparedPlayerSkin;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;

public class PlaybackManager implements ConfigListener {
    public static final int DEFAULT_STRESS_TEST_INSTANCE_COUNT = PlaybackStressTest.DEFAULT_INSTANCE_COUNT;
    public static final int MAX_STRESS_TEST_INSTANCE_COUNT = PlaybackStressTest.MAX_INSTANCE_COUNT;
    private final Map<UUID, ActivePlayback> activePlaybacks = new ConcurrentHashMap<>();
    private final List<PlaybackStateListener> stateListeners = new ArrayList<>();

    private final PlayerSkinManager playerSkinManager;
    private final PlaybackEntityController entityController = new PlaybackEntityController();
    private final PlaybackStressTest stressTest = new PlaybackStressTest(this.entityController);
    private final PlayerVisibilityService playerVisibilityService;
    private final RandomGenerator random = RandomGenerator.getDefault();
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

    public PlayResult start(ServerPlayer player, EmoteDefinition definition) {
        return switch (definition) {
            case RegisteredEmote animation -> start(player, animation);
            case RegisteredSequence sequence -> start(player, sequence);
        };
    }

    public PlayResult start(ServerPlayer player, RegisteredEmote emote) {
        return startResolved(player, emote, emote.id(), emote.playerBehavior());
    }

    private PlayResult start(ServerPlayer player, RegisteredSequence sequence) {
        return startResolved(
            player,
            sequence.compileRandom(this.random),
            sequence.id(),
            sequence.playerBehavior()
        );
    }

    private PlayResult startResolved(
        ServerPlayer player,
        RegisteredEmote emote,
        String playbackId,
        EmotePlayerBehavior playerBehavior
    ) {
        int projectedDisplayEntities = projectedDisplayEntityCount(
            activeDisplayEntityCount(),
            displayEntityCount(this.activePlaybacks.get(player.getUUID())),
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
        return startPrepared(
            player,
            emote,
            playbackId,
            playerBehavior,
            RootTransform.fromPlayer(player),
            player.isInvisible(),
            preparedSkin
        );
    }

    private PlayResult startPrepared(
        ServerPlayer player,
        RegisteredEmote emote,
        String playbackId,
        EmotePlayerBehavior playerBehavior,
        RootTransform root,
        boolean wasInvisible,
        PreparedPlayerSkin preparedSkin
    ) {
        PlaybackNodes nodes = null;
        try {
            nodes = this.entityController.create(player.level(), root, emote);
            TimelinePlayer timeline = new TimelinePlayer(emote.playbackPlan(), nodes, this.entityController);
            if (emote.animation().settings().playback().mode() == EmoteAnimation.LoopMode.SERVER_SYNC) {
                timeline.startSynchronized(Emote.SERVER.overworld().getGameTime());
            } else {
                timeline.start();
            }
            this.playerSkinManager.applySkinParts(
                nodes.nodes(),
                emote.skinParts(),
                preparedSkin
            );
            timeline.deferInitialVisibility();
            this.entityController.add(player.level(), nodes);
            if (emote.animation().settings().playback().mode() == EmoteAnimation.LoopMode.SERVER_SYNC) {
                timeline.resumeSynchronizedInterpolation();
            }
            EventPlayer events = new EventPlayer(
                emote.playbackPlan(),
                new EventCommandExecutor(player, nodes, timeline)
            );
            ActivePlayback activeEmote = new ActivePlayback(
                player.getUUID(),
                player.level().dimension(),
                playbackId,
                emote.id(),
                root.position(),
                nodes,
                timeline,
                events,
                emote.skinParts(),
                playerBehavior,
                wasInvisible
            );
            this.activePlaybacks.put(player.getUUID(), activeEmote);
            this.playerVisibilityService.start(player, activeEmote);
            startEvents(timeline, events);
            if (playbackChanged(activeEmote)) {
                return PlayResult.SUCCESS;
            }
            for (PlaybackStateListener stateListener : this.stateListeners) {
                stateListener.onStarted(player, activeEmote);
            }
            return PlayResult.SUCCESS;
        } catch (RuntimeException exception) {
            Emote.LOGGER.warn("Failed to start emote {} for {}", emote.id(), player.getScoreboardName(), exception);
            ActivePlayback activeEmote = this.activePlaybacks.remove(player.getUUID());
            if (activeEmote != null) {
                cleanupActive(activeEmote, false, PlaybackStopReason.ERROR, null);
            } else if (nodes != null) {
                this.entityController.remove(player.level(), nodes);
            }
            return PlayResult.failure("Failed to start emote.");
        }
    }

    private void refreshPlayerSkin(UUID playerUuid) {
        ActivePlayback activeEmote = this.activePlaybacks.get(playerUuid);
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
        ActivePlayback activeEmote = this.activePlaybacks.get(player.getUUID());
        if (activeEmote == null || !shouldStopFor(activeEmote.playerBehavior().stopConditions(), reason)) {
            return;
        }
        stop(player, reason);
    }

    private ActivePlayback stop(
        UUID playerUuid,
        PlaybackStopReason reason,
        @Nullable ServerPlayer knownPlayer
    ) {
        ActivePlayback activeEmote = this.activePlaybacks.remove(playerUuid);
        if (activeEmote == null) {
            return null;
        }
        cleanupActive(activeEmote, true, reason, knownPlayer);
        return activeEmote;
    }

    public ActivePlayback findActive(UUID playerUuid) {
        return this.activePlaybacks.get(playerUuid);
    }

    public void tick() {
        this.stressTest.tick();
        if (this.activePlaybacks.isEmpty()) {
            return;
        }

        List<StopRequest> stopRequests = null;
        for (ActivePlayback activeEmote : this.activePlaybacks.values()) {
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
                    activeEmote.timeline().restoreDeferredVisibility();
                    this.entityController.updateViewRotation(activeEmote.nodes(), player.getYRot());
                    TimelinePlayer.AdvanceResult result = advanceTimeline(activeEmote.timeline(), activeEmote.events());
                    if (playbackChanged(activeEmote)) {
                        continue;
                    }
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
                stopRequests.add(new StopRequest(activeEmote, stopReason));
            }
        }

        if (stopRequests != null) {
            for (StopRequest request : stopRequests) {
                stopIfCurrent(request.activeEmote(), request.reason());
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
        return advanceTimeline(timeline, events, true);
    }

    static TimelinePlayer.AdvanceResult advanceTimeline(
        TimelinePlayer timeline,
        EventPlayer events,
        boolean continueAfterLoopBoundary
    ) {
        int previousTick = timeline.currentTick();
        TimelinePlayer.AdvanceResult result = timeline.advance();
        if (result != TimelinePlayer.AdvanceResult.RESTARTED && timeline.currentTick() != previousTick) {
            events.timelineTick(timeline.currentTick());
        }
        if (result == TimelinePlayer.AdvanceResult.LOOP_BOUNDARY) {
            events.loop();
            if (continueAfterLoopBoundary) {
                result = timeline.continueAfterLoopEvent();
            }
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
        this.stressTest.stop();
        for (ActivePlayback activeEmote : List.copyOf(this.activePlaybacks.values())) {
            stopIfCurrent(activeEmote, reason);
        }
    }

    public int startStressTest(
        ServerLevel level,
        Vec3 origin,
        float yaw,
        List<RegisteredEmote> emotes,
        int instanceCount
    ) {
        return this.stressTest.start(level, origin, yaw, emotes, instanceCount);
    }

    public @Nullable PlaybackStressTestReport stopStressTest() {
        return this.stressTest.stop();
    }

    public void stopById(String id) {
        stopById(id, PlaybackStopReason.EMOTE_REMOVED);
    }

    public void stopById(String id, PlaybackStopReason reason) {
        this.stressTest.stopById(id);
        List<ActivePlayback> matchingPlaybacks = this.activePlaybacks.values().stream()
            .filter(activeEmote -> activeEmote.id().equals(id) || activeEmote.animationId().equals(id))
            .toList();
        for (ActivePlayback activeEmote : matchingPlaybacks) {
            stopIfCurrent(activeEmote, reason);
        }
    }

    private boolean playbackChanged(ActivePlayback activeEmote) {
        return this.activePlaybacks.get(activeEmote.playerUuid()) != activeEmote;
    }

    private void stopIfCurrent(ActivePlayback activeEmote, PlaybackStopReason reason) {
        if (!this.activePlaybacks.remove(activeEmote.playerUuid(), activeEmote)) {
            return;
        }
        cleanupActive(activeEmote, true, reason, null);
    }

    private void cleanupActive(
        ActivePlayback activeEmote,
        boolean notifyListeners,
        PlaybackStopReason reason,
        @Nullable ServerPlayer knownPlayer
    ) {
        try {
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
        } finally {
            try {
                activeEmote.events().stop();
            } catch (RuntimeException exception) {
                Emote.LOGGER.warn("Failed to run stop events for emote {}", activeEmote.id(), exception);
            } finally {
                ServerLevel level = Emote.SERVER.getLevel(activeEmote.levelKey());
                if (level != null) {
                    this.entityController.remove(level, activeEmote.nodes());
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

    int activeDisplayEntityCount() {
        return this.stressTest.displayEntityCount() + this.activePlaybacks.values().stream()
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

    private record StopRequest(ActivePlayback activeEmote, PlaybackStopReason reason) {
    }
}
