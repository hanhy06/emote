package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.ParticipantRole;
import io.github.hanhy06.emote.api.PlayResult;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.config.Config;
import io.github.hanhy06.emote.config.ConfigListener;
import io.github.hanhy06.emote.content.PreparedDefinition;
import io.github.hanhy06.emote.content.PreparedEmote;
import io.github.hanhy06.emote.content.PreparedSequence;
import io.github.hanhy06.emote.skin.PlayerSkinManager;
import io.github.hanhy06.emote.skin.model.PlayerSkinPreparation;
import io.github.hanhy06.emote.skin.model.PreparedPlayerSkin;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.random.RandomGenerator;

public class PlaybackManager implements ConfigListener {
    public static final int DEFAULT_STRESS_TEST_INSTANCE_COUNT = PlaybackStressTest.DEFAULT_INSTANCE_COUNT;
    public static final int MAX_STRESS_TEST_INSTANCE_COUNT = PlaybackStressTest.MAX_INSTANCE_COUNT;
    private final PlaybackSessionRegistry sessionRegistry = new PlaybackSessionRegistry();
    private final List<PlaybackStateListener> stateListeners = new ArrayList<>();

    private final PlayerSkinManager playerSkinManager;
    private final PlaybackEntityController entityController = new PlaybackEntityController();
    private final PlaybackStressTest stressTest = new PlaybackStressTest(this.entityController);
    private final PlayerVisibilityService playerVisibilityService;
    private final SceneRootResolver sceneRootResolver = new SceneRootResolver();
    private final PartnerMatcher partnerMatcher = new PartnerMatcher();
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

    public PlayResult start(ServerPlayer player, PreparedDefinition definition) {
        releasePlayerReservation(player.getUUID());
        return switch (definition) {
            case PreparedEmote animation -> start(player, animation);
            case PreparedSequence sequence -> start(player, sequence);
        };
    }

    public PlayResult start(ServerPlayer player, PreparedEmote emote) {
        return startResolved(
            player,
            emote,
            emote.id(),
            emote.playerBehavior(),
            SceneRootResolver.single(RootTransform.fromPlayer(player)),
            null
        );
    }

    private PlayResult start(ServerPlayer player, PreparedSequence sequence) {
        if (sequence.collaborative()) {
            return startCollaborative(player, sequence);
        }
        return startResolved(
            player,
            sequence.compileRandom(this.random),
            sequence.id(),
            sequence.playerBehavior(),
            SceneRootResolver.single(RootTransform.fromPlayer(player)),
            null
        );
    }

    private PlayResult startCollaborative(ServerPlayer player, PreparedSequence sequence) {
        if (findActive(player.getUUID()) == null) {
            PlaybackSession waitingSession = this.partnerMatcher.find(player, sequence.id(), this.sessionRegistry.sessions());
            if (waitingSession != null) {
                return reservePartner(player, waitingSession);
            }
        }

        Map<EmoteAnimation.NodeSpace, RootTransform> roots = this.sceneRootResolver.resolve(
            player,
            Objects.requireNonNull(sequence.source().participants(), "participants")
        );
        return startResolved(
            player,
            sequence.compiledAnimation(),
            sequence.id(),
            sequence.playerBehavior(),
            roots,
            sequence
        );
    }

    private PlayResult reservePartner(ServerPlayer player, PlaybackSession session) {
        PreparedSequence sequence = session.collaborativeSequence();
        PreparedEmote offer = sequence.compiledAnimation();
        PlayerSkinPreparation skinPreparation = this.playerSkinManager.preparePlayerSkin(
            player,
            offer.skinParts(ParticipantRole.PARTNER)
        );
        if (skinPreparation.preparing()) {
            return PlayResult.failure("Preparing player skin... " + skinPreparation.progressPercent() + "%");
        }

        PlaybackParticipant partner = new PlaybackParticipant(
            player.getUUID(),
            ParticipantRole.PARTNER,
            player.position(),
            offer.skinParts(ParticipantRole.PARTNER),
            player.isInvisible()
        );
        session.reservePartner(partner);
        this.sessionRegistry.reservePartner(session, player.getUUID());
        this.playerSkinManager.applySkinParts(
            session.nodes().nodes(),
            partner.skinParts(),
            skinPreparation.preparedPlayerSkin()
        );
        if (session.state() == PlaybackSession.State.WAITING) {
            activateMatched(session);
        }
        return PlayResult.SUCCESS;
    }

    private PlayResult startResolved(
        ServerPlayer player,
        PreparedEmote emote,
        String playbackId,
        EmotePlayerBehavior playerBehavior,
        Map<EmoteAnimation.NodeSpace, RootTransform> roots,
        @Nullable PreparedSequence collaborativeSequence
    ) {
        PlaybackSession currentSession = findActive(player.getUUID());
        int projectedDisplayEntities = projectedDisplayEntityCount(
            activeDisplayEntityCount(),
            displayEntityCount(currentSession),
            emote.displayNodeCount()
        );
        if (exceedsDisplayEntityLimit(projectedDisplayEntities, this.maxActiveDisplayEntities)) {
            return PlayResult.failure(
                "Active emote parts would exceed the server limit ("
                    + projectedDisplayEntities + "/" + this.maxActiveDisplayEntities + ")."
            );
        }

        PlayerSkinPreparation skinPreparation = this.playerSkinManager.preparePlayerSkin(
            player,
            emote.skinParts(ParticipantRole.INITIATOR)
        );
        if (skinPreparation.preparing()) {
            return PlayResult.failure("Preparing player skin... " + skinPreparation.progressPercent() + "%");
        }
        stop(player, PlaybackStopReason.REPLACED);
        return startPrepared(
            player,
            emote,
            playbackId,
            playerBehavior,
            roots,
            skinPreparation.preparedPlayerSkin(),
            collaborativeSequence
        );
    }

    private PlayResult startPrepared(
        ServerPlayer player,
        PreparedEmote emote,
        String playbackId,
        EmotePlayerBehavior playerBehavior,
        Map<EmoteAnimation.NodeSpace, RootTransform> roots,
        PreparedPlayerSkin preparedSkin,
        @Nullable PreparedSequence collaborativeSequence
    ) {
        PlaybackNodes nodes = null;
        PlaybackSession session = null;
        try {
            nodes = this.entityController.create(player.level(), roots, emote);
            TimelinePlayer timeline = new TimelinePlayer(emote.playbackPlan(), nodes, this.entityController);
            if (emote.animation().settings().playback().mode() == EmoteAnimation.LoopMode.SERVER_SYNC) {
                timeline.startSynchronized(Emote.SERVER.overworld().getGameTime());
            } else {
                timeline.start();
            }
            this.playerSkinManager.applySkinParts(
                nodes.nodes(),
                emote.skinParts(ParticipantRole.INITIATOR),
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
            PlaybackParticipant initiator = new PlaybackParticipant(
                player.getUUID(),
                ParticipantRole.INITIATOR,
                roots.get(EmoteAnimation.NodeSpace.SCENE).position(),
                emote.skinParts(ParticipantRole.INITIATOR),
                player.isInvisible()
            );
            session = new PlaybackSession(
                UUID.randomUUID(),
                player.level().dimension(),
                playbackId,
                emote.id(),
                nodes,
                timeline,
                events,
                playerBehavior,
                initiator,
                collaborativeSequence
            );
            this.sessionRegistry.register(session);
            this.playerVisibilityService.start(player, session, initiator);
            startEvents(timeline, events);
            if (playbackChanged(session)) {
                return PlayResult.SUCCESS;
            }
            for (PlaybackStateListener stateListener : this.stateListeners) {
                stateListener.onStarted(player, session, initiator);
            }
            return PlayResult.SUCCESS;
        } catch (RuntimeException exception) {
            Emote.LOGGER.warn("Failed to start emote {} for {}", emote.id(), player.getScoreboardName(), exception);
            if (session != null && removeSession(session)) {
                cleanupSession(session, false, PlaybackStopReason.ERROR, null);
            } else if (nodes != null) {
                this.entityController.remove(player.level(), nodes);
            }
            return PlayResult.failure("Failed to start emote.");
        }
    }

    private void refreshPlayerSkin(UUID playerUuid) {
        PlaybackSession session = findActive(playerUuid);
        if (session == null) {
            return;
        }
        PlaybackParticipant participant = session.participant(playerUuid);
        ServerPlayer player = Emote.SERVER.getPlayerList().getPlayer(playerUuid);
        if (player == null || participant == null) {
            return;
        }
        PlayerSkinPreparation preparation = this.playerSkinManager.preparePlayerSkin(
            player,
            participant.skinParts()
        );
        this.playerSkinManager.applySkinParts(
            session.nodes().nodes(),
            participant.skinParts(),
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
            return releasePlayerReservation(playerUuid);
        }
        if (!removeSession(session)) {
            return null;
        }
        cleanupSession(session, true, reason, knownPlayer);
        return session;
    }

    public PlaybackSession findActive(UUID playerUuid) {
        return this.sessionRegistry.findParticipant(playerUuid);
    }

    public void tick() {
        this.stressTest.tick();
        if (this.sessionRegistry.isEmpty()) {
            return;
        }

        List<StopRequest> stopRequests = null;
        for (PlaybackSession session : this.sessionRegistry.sessions()) {
            PlaybackParticipant initiator = session.initiator();
            ServerPlayer player = Emote.SERVER.getPlayerList().getPlayer(initiator.playerUuid());
            PlaybackStopReason stopReason = null;
            for (PlaybackParticipant participant : session.participants()) {
                ServerPlayer participantPlayer = Emote.SERVER.getPlayerList().getPlayer(participant.playerUuid());
                if (!canKeepPlaying(participantPlayer, session)) {
                    stopReason = PlaybackStopReason.PLAYER_UNAVAILABLE;
                    break;
                }
                if (session.playerBehavior().stopConditions().submerge() && participantPlayer.isUnderWater()) {
                    stopReason = PlaybackStopReason.SUBMERGED;
                    break;
                }
                if (hasMovedDuringPlayback(participantPlayer, session, participant)) {
                    stopReason = PlaybackStopReason.MOVED;
                    break;
                }
            }
            if (stopReason == null) {
                try {
                    session.timeline().restoreDeferredVisibility();
                    if (session.state() == PlaybackSession.State.SOLO) {
                        this.entityController.updateViewRotation(session.nodes(), player.getYRot());
                    }

                    if (session.state() == PlaybackSession.State.WAITING) {
                        if (session.reservedPartner() != null) {
                            activateMatched(session);
                        } else if (session.tickTimeout()) {
                            activateTimeout(session);
                        }
                    } else {
                        TimelinePlayer.AdvanceResult result = advanceTimeline(session.timeline(), session.events());
                        if (playbackChanged(session)) {
                            continue;
                        }
                        if (result == TimelinePlayer.AdvanceResult.FINISHED) {
                            stopReason = handleFinishedTimeline(session);
                        }
                    }

                    if (stopReason == null && !playbackChanged(session)) {
                        for (PlaybackParticipant participant : session.participants()) {
                            ServerPlayer participantPlayer = Emote.SERVER.getPlayerList().getPlayer(participant.playerUuid());
                            if (participantPlayer != null) {
                                this.playerVisibilityService.tick(participantPlayer, session, participant);
                            }
                        }
                    }
                } catch (RuntimeException exception) {
                    Emote.LOGGER.warn("Failed while playing emote {}", session.id(), exception);
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
        return switch (session.state()) {
            case SOLO, MATCHED, TIMEOUT -> PlaybackStopReason.FINISHED;
            case OFFERING -> {
                if (session.reservedPartner() == null || !activateMatched(session)) {
                    session.enterWaiting();
                }
                yield null;
            }
            case WAITING -> throw new IllegalStateException("Waiting sessions do not advance their timeline");
        };
    }

    private boolean activateMatched(PlaybackSession session) {
        PlaybackParticipant reservedPartner = Objects.requireNonNull(session.reservedPartner(), "reservedPartner");
        ServerPlayer player = Emote.SERVER.getPlayerList().getPlayer(reservedPartner.playerUuid());
        if (player == null || !player.isAlive() || !this.partnerMatcher.stillMatches(session, player)) {
            releaseReservedPartner(session);
            return false;
        }

        PreparedSequence sequence = session.collaborativeSequence();
        PreparedEmote matched = sequence.compileMatchedRandom(this.random);
        BranchPlayback playback = createBranchPlayback(session, matched);
        PlaybackParticipant partner = session.activateReservedPartner(playback.timeline(), playback.events());
        this.sessionRegistry.activatePartner(session, partner.playerUuid());
        this.playerVisibilityService.start(player, session, partner);
        startEvents(playback.timeline(), playback.events());
        this.entityController.activateSpace(session.nodes(), EmoteAnimation.NodeSpace.PARTNER);
        for (PlaybackStateListener stateListener : this.stateListeners) {
            stateListener.onStarted(player, session, partner);
        }
        return true;
    }

    private void activateTimeout(PlaybackSession session) {
        PreparedSequence sequence = session.collaborativeSequence();
        PreparedEmote timeout = sequence.compileTimeoutRandom(this.random);
        BranchPlayback playback = createBranchPlayback(session, timeout);
        session.beginTimeout(playback.timeline(), playback.events());
        startEvents(playback.timeline(), playback.events());
    }

    private BranchPlayback createBranchPlayback(PlaybackSession session, PreparedEmote emote) {
        TimelinePlayer timeline = new TimelinePlayer(emote.playbackPlan(), session.nodes(), this.entityController);
        timeline.start();
        EventPlayer events = new EventPlayer(
            emote.playbackPlan(),
            new EventCommandExecutor(sessionInitiatorPlayer(session), session.nodes(), timeline)
        );
        return new BranchPlayback(timeline, events);
    }

    private void releaseReservedPartner(PlaybackSession session) {
        PlaybackParticipant partner = session.releaseReservedPartner();
        if (partner != null) {
            this.sessionRegistry.releasePartner(session, partner.playerUuid());
        }
    }

    private @Nullable PlaybackSession releasePlayerReservation(UUID playerUuid) {
        PlaybackSession session = this.sessionRegistry.findReservation(playerUuid);
        if (session == null) {
            return null;
        }
        PlaybackParticipant partner = session.reservedPartner();
        if (partner == null || !partner.playerUuid().equals(playerUuid)) {
            throw new IllegalStateException("Partner reservation does not match its session");
        }
        releaseReservedPartner(session);
        return session;
    }

    private ServerPlayer sessionInitiatorPlayer(PlaybackSession session) {
        ServerPlayer initiator = Emote.SERVER.getPlayerList().getPlayer(session.initiator().playerUuid());
        if (initiator == null) {
            throw new IllegalStateException("Initiator is unavailable");
        }
        return initiator;
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
        for (PlaybackSession session : List.copyOf(this.sessionRegistry.sessions())) {
            stopIfCurrent(session, reason);
        }
    }

    public int startStressTest(
        ServerLevel level,
        Vec3 origin,
        float yaw,
        List<PreparedEmote> emotes,
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

    private void stopIfCurrent(PlaybackSession session, PlaybackStopReason reason) {
        if (!removeSession(session)) {
            return;
        }
        cleanupSession(session, true, reason, null);
    }

    private boolean removeSession(PlaybackSession session) {
        return this.sessionRegistry.remove(session);
    }

    private void cleanupSession(
        PlaybackSession session,
        boolean notifyListeners,
        PlaybackStopReason reason,
        @Nullable ServerPlayer knownPlayer
    ) {
        try {
            for (PlaybackParticipant participant : session.participants()) {
                ServerPlayer player = knownPlayer != null && knownPlayer.getUUID().equals(participant.playerUuid())
                    ? knownPlayer
                    : Emote.SERVER.getPlayerList().getPlayer(participant.playerUuid());
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
                session.events().stop();
            } catch (RuntimeException exception) {
                Emote.LOGGER.warn("Failed to run stop events for emote {}", session.id(), exception);
            } finally {
                ServerLevel level = Emote.SERVER.getLevel(session.levelKey());
                if (level != null) {
                    this.entityController.remove(level, session.nodes());
                }
            }
        }
    }

    private boolean canKeepPlaying(ServerPlayer player, PlaybackSession session) {
        return player != null
            && player.isAlive()
            && player.level().dimension().equals(session.levelKey());
    }

    private boolean hasMovedDuringPlayback(ServerPlayer player, PlaybackSession session, PlaybackParticipant participant) {
        double movementDistance = session.playerBehavior().stopConditions().movementDistance();
        if (movementDistance == 0.0D) {
            return false;
        }
        Vec3 currentPosition = player.position();
        Vec3 startPosition = participant.startPosition();
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
        return this.stressTest.displayEntityCount() + this.sessionRegistry.sessions().stream()
            .mapToInt(this::displayEntityCount)
            .sum();
    }

    static boolean exceedsDisplayEntityLimit(int projectedDisplayEntities, int limit) {
        return limit > 0 && projectedDisplayEntities > limit;
    }

    static int projectedDisplayEntityCount(int activeDisplayEntities, int replacedDisplayEntities, int requestedDisplayEntities) {
        return activeDisplayEntities - replacedDisplayEntities + requestedDisplayEntities;
    }

    private int displayEntityCount(@Nullable PlaybackSession session) {
        if (session == null) {
            return 0;
        }
        return (int) session.nodes().nodes().values().stream()
            .filter(node -> !node.isAnchor())
            .count();
    }

    private record StopRequest(PlaybackSession session, PlaybackStopReason reason) {
    }

    private record BranchPlayback(TimelinePlayer timeline, EventPlayer events) {
    }
}
