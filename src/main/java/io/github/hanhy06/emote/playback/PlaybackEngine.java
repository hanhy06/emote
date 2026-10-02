package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.ParticipantRole;
import io.github.hanhy06.emote.api.PlayResult;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.config.Config;
import io.github.hanhy06.emote.config.ConfigListener;
import io.github.hanhy06.emote.content.PlayableEmote;
import io.github.hanhy06.emote.content.PreparedAnimation;
import io.github.hanhy06.emote.content.PreparedSequence;
import io.github.hanhy06.emote.playback.molang.PlayerMolangQueries;
import io.github.hanhy06.emote.playback.runtime.*;
import io.github.hanhy06.emote.playback.session.*;
import io.github.hanhy06.emote.playback.stress.PlaybackStressTest;
import io.github.hanhy06.emote.playback.stress.PlaybackStressTestReport;
import io.github.hanhy06.emote.playback.timeline.EventCommandExecutor;
import io.github.hanhy06.emote.skin.PlayerSkinManager;
import io.github.hanhy06.emote.skin.SkinBinding;
import io.github.hanhy06.emote.skin.model.PlayerSkinPreparation;
import io.github.hanhy06.emote.skin.model.PreparedPlayerSkin;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;

public class PlaybackEngine implements ConfigListener {
    public static final int DEFAULT_STRESS_TEST_INSTANCE_COUNT = PlaybackStressTest.DEFAULT_INSTANCE_COUNT;
    public static final int MAX_STRESS_TEST_INSTANCE_COUNT = PlaybackStressTest.MAX_INSTANCE_COUNT;
    public static final int DEFAULT_STRESS_TEST_PACKET_FANOUT = PlaybackStressTest.DEFAULT_PACKET_FANOUT;
    public static final int MAX_STRESS_TEST_PACKET_FANOUT = PlaybackStressTest.MAX_PACKET_FANOUT;
    private final PlaybackSessionRegistry sessionRegistry = new PlaybackSessionRegistry();
    private final List<PlaybackStateListener> stateListeners = new ArrayList<>();
    private final Set<UUID> closingPlayers = new HashSet<>();

    private final PlayerSkinManager playerSkinManager;
    private final PlaybackEntityController entityController = new PlaybackEntityController();
    private final PlaybackStressTest stressTest = new PlaybackStressTest(this.entityController);
    private final PlayerVisibilityService playerVisibilityService;
    private final RandomGenerator random = RandomGenerator.getDefault();
    private int maxActiveDisplayEntities = Config.DEFAULT_MAX_ACTIVE_DISPLAY_ENTITIES;
    private final CallbackRegistry callbackRegistry = new CallbackRegistry();

    public CallbackRegistry callbackRegistry() {
        return this.callbackRegistry;
    }

    public PlaybackEngine(PlayerSkinManager playerSkinManager) {
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

    public PlayResult start(ServerPlayer player, PlayableEmote definition) {
        if (this.closingPlayers.contains(player.getUUID())) {
            return PlayResult.failure("Your previous emote is still closing.");
        }
        return switch (definition) {
            case PreparedAnimation animation -> start(player, animation);
            case PreparedSequence sequence -> start(player, sequence);
        };
    }

    public PlayResult start(ServerPlayer player, PreparedAnimation emote) {
        return startResolved(
            player,
            emote,
            emote.id(),
            emote.playerBehavior(),
            SceneRootResolver.single(RootTransform.fromPlayer(player))
        );
    }

    private PlayResult start(ServerPlayer player, PreparedSequence sequence) {
        if (sequence.hasPartner()) {
            return PlayResult.failure("Two-player matching is no longer supported.");
        }
        return startResolved(
            player,
            sequence.compile(this.random),
            sequence.id(),
            sequence.playerBehavior(),
            SceneRootResolver.single(RootTransform.fromPlayer(player))
        );
    }

    private PlayResult startResolved(
        ServerPlayer player,
        PreparedAnimation emote,
        String playbackId,
        EmotePlayerBehavior playerBehavior,
        Map<EmoteAnimation.NodeSpace, RootTransform> roots
    ) {
        if (this.closingPlayers.contains(player.getUUID())) {
            return PlayResult.failure("Your previous emote is still closing.");
        }
        PlaybackSession currentSession = findActive(player.getUUID());
        if (currentSession != null && currentSession.isInvokingCallback()) {
            return PlayResult.failure("Cannot replace an emote from its own callback.");
        }
        int projectedDisplayEntities = projectedDisplayEntityCount(
            activeDisplayEntityCount(),
            displayEntityCount(currentSession),
            emote.displayNodeCount()
        );
        if (exceedsDisplayEntityLimit(projectedDisplayEntities, this.maxActiveDisplayEntities)) {
            return PlayResult.failure("Too many emotes are active right now. Try again shortly.");
        }

        List<CallbackRegistry.Binding> callbackBindings;
        Map<PreparedAnimation, List<CallbackRegistry.Binding>> animationBindings = new HashMap<>();
        try {
            callbackBindings = this.callbackRegistry.resolve(emote.animation().callbacks());
            for (PreparedAnimation.PlaybackSegment segment : emote.playbackSegments()) {
                animationBindings.computeIfAbsent(segment.animation(), animation -> this.callbackRegistry.resolve(animation.animation().callbacks()));
            }
        } catch (IllegalArgumentException exception) {
            return PlayResult.failure(exception.getMessage());
        }
        PlayerSkinPreparation skinPreparation = this.playerSkinManager.preparePlayerSkin(
            player,
            emote.skinBindings(ParticipantRole.INITIATOR)
        );
        if (skinPreparation.preparing()) {
            return PlayResult.failure("Preparing your skin… " + skinPreparation.progressPercent() + "%");
        }
        stop(player, PlaybackStopReason.REPLACED);
        return startPrepared(
            player,
            emote,
            playbackId,
            playerBehavior,
            roots,
            skinPreparation.preparedPlayerSkin(),
            callbackBindings,
            animationBindings
        );
    }

    private PlayResult startPrepared(
        ServerPlayer player,
        PreparedAnimation emote,
        String playbackId,
        EmotePlayerBehavior playerBehavior,
        Map<EmoteAnimation.NodeSpace, RootTransform> roots,
        PreparedPlayerSkin preparedSkin,
        List<CallbackRegistry.Binding> callbackBindings,
        Map<PreparedAnimation, List<CallbackRegistry.Binding>> animationBindings
    ) {
        PlaybackNodes nodes = null;
        PlaybackSession session = null;
        boolean startedNotified = false;
        try {
            nodes = this.entityController.create(player.level(), roots, emote);
            AnimationPlayer timeline = new AnimationPlayer(
                emote,
                new EntityTimelineTarget(emote, nodes, this.entityController),
                PlayerMolangQueries.forPlayer(player)
            );
            timeline.bindEvents(new EventCommandExecutor(player, nodes, timeline));
            if (emote.animation().settings().playback().mode() == EmoteAnimation.LoopMode.SERVER_SYNC) {
                timeline.startSynchronized(EmoteMod.SERVER.overworld().getGameTime());
            } else {
                timeline.start();
            }
            this.entityController.applySkin(
                nodes,
                emote.skinBindings(ParticipantRole.INITIATOR),
                preparedSkin
            );
            timeline.deferInitialVisibility();
            this.entityController.add(player.level(), nodes);
            PlaybackParticipant initiator = new PlaybackParticipant(
                player.getUUID(),
                ParticipantRole.INITIATOR,
                roots.get(EmoteAnimation.NodeSpace.SCENE).position(),
                emote.skinBindings(ParticipantRole.INITIATOR),
                player.isInvisible()
            );
            session = new PlaybackSession(
                UUID.randomUUID(),
                player.level().dimension(),
                playbackId,
                emote.id(),
                nodes,
                timeline,
                playerBehavior,
                initiator
            );
            session.bindCallbacks(callbackBindings, animationBindings, EmoteMod.SERVER.getTickCount());
            this.sessionRegistry.register(session);
            this.playerVisibilityService.start(player, session, initiator);
            if (!notifyStarted(player, session, initiator)) {
                return new PlayResult.Success(session.playbackInfo(player.getUUID()));
            }
            startedNotified = true;
            session.startPlayback();
            return new PlayResult.Success(session.playbackInfo(player.getUUID()));
        } catch (RuntimeException exception) {
            EmoteMod.LOGGER.warn("Failed to start emote {} for player {}", emote.id(), player.getScoreboardName(), exception);
            if (session != null && this.sessionRegistry.remove(session)) {
                cleanupSession(session, startedNotified, PlaybackStopReason.ERROR, null);
            } else if (nodes != null) {
                this.entityController.remove(player.level(), nodes);
            }
            return PlayResult.failure("Something went wrong while starting the emote.");
        }
    }

    private void refreshPlayerSkin(UUID playerUuid) {
        PlaybackSession session = findActive(playerUuid);
        if (session == null) {
            return;
        }
        PlaybackParticipant participant = session.participant(playerUuid);
        ServerPlayer player = EmoteMod.SERVER.getPlayerList().getPlayer(playerUuid);
        if (player == null || participant == null) {
            return;
        }
        PlayerSkinPreparation preparation = this.playerSkinManager.preparePlayerSkin(
            player,
            participant.skinBindings()
        );
        this.entityController.applySkin(
            session.nodes(),
            participant.skinBindings(),
            preparation.preparedPlayerSkin()
        );
    }

    public PlaybackSession stop(ServerPlayer player) {
        return stop(player, PlaybackStopReason.MANUAL);
    }

    public PlaybackSession stop(ServerPlayer player, PlaybackStopReason reason) {
        return stop(player.getUUID(), reason, player);
    }

    public void interrupt(ServerPlayer player, PlaybackStopReason reason) {
        PlaybackSession session = findActive(player.getUUID());
        if (session == null || !shouldStopFor(session.playerBehavior().stopConditions(), reason)) {
            return;
        }
        stop(player, reason);
    }

    private PlaybackSession stop(
        UUID playerUuid,
        PlaybackStopReason reason,
        @Nullable ServerPlayer knownPlayer
    ) {
        PlaybackSession session = findActive(playerUuid);
        if (session == null) {
            return null;
        }
        if (supportsOutro(reason) && requestOutro(session, reason)) {
            return session;
        }
        if (!this.sessionRegistry.remove(session)) {
            return null;
        }
        cleanupSession(session, true, reason, knownPlayer);
        return session;
    }

    public PlaybackSession findActive(UUID playerUuid) {
        return this.sessionRegistry.findParticipant(playerUuid);
    }

    public @Nullable PlaybackSession findSession(UUID sessionId) {
        return this.sessionRegistry.findSession(sessionId);
    }

    public void tick() {
        this.stressTest.tick();
        if (this.sessionRegistry.isEmpty()) {
            return;
        }

        List<StopRequest> stopRequests = null;
        for (PlaybackSession session : this.sessionRegistry.sessions()) {
            PlaybackParticipant initiator = session.initiator();
            ServerPlayer player = EmoteMod.SERVER.getPlayerList().getPlayer(initiator.playerUuid());
            PlaybackStopReason stopReason = null;
            boolean movementOutroRequested = false;
            for (PlaybackParticipant participant : session.participants()) {
                ServerPlayer participantPlayer = participant == initiator
                    ? player
                    : EmoteMod.SERVER.getPlayerList().getPlayer(participant.playerUuid());
                if (!canKeepPlaying(participantPlayer, session)) {
                    stopReason = PlaybackStopReason.PLAYER_UNAVAILABLE;
                    break;
                }
                if (session.playerBehavior().stopConditions().submerge() && participantPlayer.isUnderWater()) {
                    stopReason = PlaybackStopReason.SUBMERGED;
                    break;
                }
                MovementResult movement = movementResult(participantPlayer, session, participant);
                if (movement == MovementResult.IMMEDIATE_STOP) {
                    stopReason = PlaybackStopReason.MOVED;
                    break;
                }
                if (movement == MovementResult.REQUEST_OUTRO) {
                    movementOutroRequested = true;
                }
            }
            if (stopReason == null && movementOutroRequested) {
                if (!requestOutro(session, PlaybackStopReason.MOVED)) {
                    stopReason = PlaybackStopReason.MOVED;
                }
            }
            if (stopReason == null) {
                try {
                    if (!session.tick(EmoteMod.SERVER.getTickCount())) {
                        continue;
                    }
                    if (session.playerBehavior().stopConditions().movementDistance() == 0.0D) {
                        this.entityController.moveSceneTo(session.nodes(), player.position());
                    }
                    session.animation().restoreDeferredVisibility();
                    this.entityController.updateViewRotation(
                        session.nodes(),
                        player.getYRot(),
                        session.animation().rotationDeadzone()
                    );

                    for (PlaybackParticipant participant : session.participants()) {
                        ServerPlayer participantPlayer = participant == initiator
                            ? player
                            : EmoteMod.SERVER.getPlayerList().getPlayer(participant.playerUuid());
                        this.playerVisibilityService.tick(participantPlayer, session, participant);
                    }
                    AnimationPlayer.AdvanceResult result = session.animation().advance();
                    if (playbackChanged(session)) continue;
                    boolean timelineFinished = result == AnimationPlayer.AdvanceResult.FINISHED;

                    if (stopReason == null && !playbackChanged(session)) {
                        session.tickCallbacks();
                        if (playbackChanged(session)) continue;
                        if (timelineFinished) stopReason = handleFinishedTimeline(session);
                    }
                } catch (RuntimeException exception) {
                    EmoteMod.LOGGER.warn("Failed to play emote {}", session.id(), exception);
                    stopReason = PlaybackStopReason.ERROR;
                }
            }
            if (stopReason != null) {
                if (stopRequests == null) {
                    stopRequests = new ArrayList<>();
                }
                stopRequests.add(new StopRequest(session, stopReason));
            }
        }

        if (stopRequests != null) {
            for (StopRequest request : stopRequests) {
                stopIfCurrent(request.session(), request.reason());
            }
        }
    }

    private @Nullable PlaybackStopReason handleFinishedTimeline(PlaybackSession session) {
        if (session.pendingStopReason() != null) {
            return session.pendingStopReason();
        }
        return PlaybackStopReason.FINISHED;
    }

    public void stopAll() {
        stopAll(PlaybackStopReason.MANUAL);
    }

    public void stopAll(PlaybackStopReason reason) {
        this.stressTest.stop();
        for (PlaybackSession session : List.copyOf(this.sessionRegistry.sessions())) {
            if (supportsOutro(reason) && requestOutro(session, reason)) {
                continue;
            }
            stopIfCurrent(session, reason);
        }
    }

    public PlaybackStressTest.StartResult startStressTest(
        ServerLevel level,
        Vec3 origin,
        float yaw,
        List<PreparedAnimation> emotes,
        int durationTicks,
        int instanceCount,
        int packetFanout,
        @Nullable PreparedPlayerSkin preparedSkin,
        Consumer<PlaybackStressTestReport> completion
    ) {
        return this.stressTest.start(
            level,
            origin,
            yaw,
            emotes,
            durationTicks,
            instanceCount,
            packetFanout,
            preparedSkin,
            completion
        );
    }

    public PlaybackStressTest.StartResult startStressTestByDisplayCount(
        ServerLevel level,
        Vec3 origin,
        float yaw,
        List<PreparedAnimation> emotes,
        int durationTicks,
        int targetDisplayEntityCount,
        int packetFanout,
        @Nullable PreparedPlayerSkin preparedSkin,
        Consumer<PlaybackStressTestReport> completion
    ) {
        return this.stressTest.startByDisplayCount(
            level,
            origin,
            yaw,
            emotes,
            durationTicks,
            targetDisplayEntityCount,
            packetFanout,
            preparedSkin,
            completion
        );
    }

    public PlayerSkinPreparation prepareStressTestSkin(ServerPlayer player, List<PreparedAnimation> emotes) {
        List<SkinBinding> bindings = emotes.stream().flatMap(emote -> emote.skinBindings().stream()).distinct().toList();
        return this.playerSkinManager.preparePlayerSkin(player, bindings);
    }

    public @Nullable PlaybackStressTestReport stopStressTest() {
        return this.stressTest.stop();
    }

    public void stopById(String id) {
        stopById(id, PlaybackStopReason.EMOTE_REMOVED);
    }

    public void stopById(String id, PlaybackStopReason reason) {
        this.stressTest.stopById(id);
        List<PlaybackSession> matchingPlaybacks = this.sessionRegistry.sessions().stream()
            .filter(session -> session.id().equals(id) || session.animationId().equals(id))
            .toList();
        for (PlaybackSession session : matchingPlaybacks) {
            stopIfCurrent(session, reason);
        }
    }

    private boolean playbackChanged(PlaybackSession session) {
        return !this.sessionRegistry.contains(session);
    }

    private boolean notifyStarted(ServerPlayer player, PlaybackSession session, PlaybackParticipant participant) {
        for (PlaybackStateListener stateListener : this.stateListeners) {
            stateListener.onStarted(player, session, participant);
            if (playbackChanged(session)) {
                return false;
            }
        }
        return true;
    }

    private void stopIfCurrent(PlaybackSession session, PlaybackStopReason reason) {
        if (!this.sessionRegistry.remove(session)) {
            return;
        }
        cleanupSession(session, true, reason, null);
    }

    private void cleanupSession(
        PlaybackSession session,
        boolean notifyListeners,
        PlaybackStopReason reason,
        @Nullable ServerPlayer knownPlayer
    ) {
        if (!session.beginClose(reason)) return;
        for (PlaybackParticipant participant : session.participants()) {
            this.closingPlayers.add(participant.playerUuid());
        }
        if (session.deferCleanup(() -> finishCleanupSession(session, notifyListeners, reason, knownPlayer))) return;
        finishCleanupSession(session, notifyListeners, reason, knownPlayer);
    }

    private void finishCleanupSession(
        PlaybackSession session,
        boolean notifyListeners,
        PlaybackStopReason reason,
        @Nullable ServerPlayer knownPlayer
    ) {
        try {
            try {
                session.animation().stop(reason);
            } catch (RuntimeException exception) {
                EmoteMod.LOGGER.warn("Failed to run stop events for emote {}", session.id(), exception);
            }
            session.closeCallbacks();
            for (PlaybackParticipant participant : session.participants()) {
                ServerPlayer player = knownPlayer != null && knownPlayer.getUUID().equals(participant.playerUuid())
                    ? knownPlayer
                    : EmoteMod.SERVER.getPlayerList().getPlayer(participant.playerUuid());
                if (player == null) {
                    continue;
                }
                this.playerVisibilityService.stop(player, session, participant);
                if (notifyListeners) {
                    for (PlaybackStateListener stateListener : this.stateListeners) {
                        stateListener.onStopped(player, session, participant, reason);
                    }
                }
            }
        } finally {
            try {
                ServerLevel level = EmoteMod.SERVER.getLevel(session.levelKey());
                if (level != null) {
                    this.entityController.remove(level, session.nodes());
                }
            } finally {
                session.completeClose();
                for (PlaybackParticipant participant : session.participants()) {
                    this.closingPlayers.remove(participant.playerUuid());
                }
            }
        }
    }

    private boolean canKeepPlaying(ServerPlayer player, PlaybackSession session) {
        return player != null
            && player.isAlive()
            && player.level().dimension().equals(session.levelKey());
    }

    private MovementResult movementResult(ServerPlayer player, PlaybackSession session, PlaybackParticipant participant) {
        double movementDistance = session.playerBehavior().stopConditions().movementDistance();
        if (movementDistance == 0.0D) {
            return MovementResult.NONE;
        }
        Vec3 currentPosition = player.position();
        Vec3 startPosition = participant.startPosition();
        double xDistance = currentPosition.x - startPosition.x;
        double zDistance = currentPosition.z - startPosition.z;
        double horizontalDistanceSquared = xDistance * xDistance + zDistance * zDistance;
        return movementResult(horizontalDistanceSquared, movementDistance, session.pendingStopReason() != null);
    }

    static MovementResult movementResult(double horizontalDistanceSquared, double movementDistance, boolean outroStarted) {
        if (movementDistance == 0.0D) {
            return MovementResult.NONE;
        }
        double immediateStopDistance = movementDistance * 1.3D;
        if (horizontalDistanceSquared > immediateStopDistance * immediateStopDistance) {
            return MovementResult.IMMEDIATE_STOP;
        }
        if (!outroStarted && horizontalDistanceSquared > movementDistance * movementDistance) {
            return MovementResult.REQUEST_OUTRO;
        }
        return MovementResult.NONE;
    }

    private boolean requestOutro(PlaybackSession session, PlaybackStopReason reason) {
        if (!session.requestStop(reason)) {
            return false;
        }
        return true;
    }

    private static boolean supportsOutro(PlaybackStopReason reason) {
        return reason == PlaybackStopReason.MANUAL
            || reason == PlaybackStopReason.MOVED
            || reason == PlaybackStopReason.JUMPED;
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

    public int activeDisplayEntityCount() {
        return this.stressTest.displayEntityCount() + this.sessionRegistry.activeDisplayEntityCount();
    }

    public int activeSessionCount() {
        return this.sessionRegistry.activeSessionCount();
    }

    public int activeParticipantCount() {
        return this.sessionRegistry.activeParticipantCount();
    }

    static boolean exceedsDisplayEntityLimit(int projectedDisplayEntities, int limit) {
        return limit > 0 && projectedDisplayEntities > limit;
    }

    static int projectedDisplayEntityCount(int activeDisplayEntities, int replacedDisplayEntities, int requestedDisplayEntities) {
        return activeDisplayEntities - replacedDisplayEntities + requestedDisplayEntities;
    }

    private int displayEntityCount(@Nullable PlaybackSession session) {
        return session == null ? 0 : session.nodes().displayEntityCount();
    }

    private record StopRequest(PlaybackSession session, PlaybackStopReason reason) {
    }

    enum MovementResult {
        NONE,
        REQUEST_OUTRO,
        IMMEDIATE_STOP
    }

}
