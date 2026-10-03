package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.api.PlaybackPlacement;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.config.Config;
import io.github.hanhy06.emote.config.ConfigListener;
import io.github.hanhy06.emote.content.PreparedEmote;
import io.github.hanhy06.emote.playback.molang.MolangQuerySource;
import io.github.hanhy06.emote.playback.runtime.EntityTimelineTarget;
import io.github.hanhy06.emote.playback.runtime.PlaybackEntityController;
import io.github.hanhy06.emote.playback.runtime.PlaybackNodes;
import io.github.hanhy06.emote.playback.runtime.RootTransform;
import io.github.hanhy06.emote.playback.session.PlaybackSession;
import io.github.hanhy06.emote.playback.stress.PlaybackStressTest;
import io.github.hanhy06.emote.playback.timeline.EventCommandExecutor;
import io.github.hanhy06.emote.skin.model.PreparedPlayerSkin;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.*;

public final class PlaybackEngine implements ConfigListener {
    private final Map<UUID, ActivePlayback> activePlaybacks = new HashMap<>();
    private int activeDisplayEntities;
    private final PlaybackEntityController entityController = new PlaybackEntityController();
    private final PlaybackStressTest stressTest = new PlaybackStressTest(this.entityController);
    private final CallbackRegistry callbackRegistry = new CallbackRegistry();
    private int maxActiveDisplayEntities = Config.DEFAULT_MAX_ACTIVE_DISPLAY_ENTITIES;
    private Lifecycle stateListener = Lifecycle.NONE;

    public interface Lifecycle {
        Lifecycle NONE = new Lifecycle() {};
        default void onStarted(PlaybackSession session) {}
        default @Nullable PlaybackStopReason beforeTick(PlaybackSession session) { return null; }
        default @Nullable RootTransform resolveActorPlacement(PlaybackSession session) { return null; }
        default void prepareFrame(PlaybackSession session) {}
        default void onClosing(PlaybackSession session) {}
        default void onStopped(PlaybackSession session, PlaybackStopReason reason) {}
    }

    public record Request(ServerLevel level, RootTransform root, PreparedEmote emote, String emoteId,
                          Map<String, Entity> actors, MolangQuerySource queries, @Nullable CommandSourceStack commandSource,
                          @Nullable PreparedPlayerSkin skin, Lifecycle lifecycle, PlaybackPlacement.Mode placementMode) {
        public Request {
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(emote, "emote");
            Objects.requireNonNull(emoteId, "emoteId");
            actors = Map.copyOf(actors);
            Objects.requireNonNull(queries, "queries");
            Objects.requireNonNull(lifecycle, "lifecycle");
            Objects.requireNonNull(placementMode, "placementMode");
        }
        public Request(ServerLevel level, RootTransform root, PreparedEmote emote) {
            this(level, root, emote, emote.id(), Map.of(), MolangQuerySource.EMPTY, null, null, Lifecycle.NONE, PlaybackPlacement.Mode.EXTERNAL);
        }
    }

    public sealed interface StartResult {
        record Success(PlaybackSession session) implements StartResult {}
        record Failure(FailureReason reason, String message) implements StartResult {}
    }

    public enum FailureReason { DISPLAY_LIMIT, START_REJECTED }

    public CallbackRegistry callbackRegistry() { return this.callbackRegistry; }
    public PlaybackEntityController entities() { return this.entityController; }
    public void setStateListener(Lifecycle listener) { this.stateListener = Objects.requireNonNull(listener, "listener"); }
    @Override public void onConfigReload(Config config) { this.maxActiveDisplayEntities = config.maxActiveDisplayEntities(); }

    public StartResult start(Request request, @Nullable PlaybackSession replacedSession) {
        PreparedEmote emote = request.emote();
        if (replacedSession != null && !contains(replacedSession)) {
            return new StartResult.Failure(FailureReason.START_REJECTED, "The playback being replaced is no longer active.");
        }
        if (replacedSession != null && replacedSession.isInvokingCallback()) {
            return new StartResult.Failure(FailureReason.START_REJECTED, "Cannot replace an emote from its own callback.");
        }
        int projected = projectedDisplayEntityCount(activeDisplayEntityCount(),
            replacedSession == null ? 0 : replacedSession.nodes().displayEntityCount(), emote.displayNodeCount());
        if (exceedsDisplayEntityLimit(projected, this.maxActiveDisplayEntities)) {
            return new StartResult.Failure(FailureReason.DISPLAY_LIMIT, "Too many emotes are active right now. Try again shortly.");
        }
        List<CallbackRegistry.Binding> bindings;
        Map<PreparedEmote, List<CallbackRegistry.Binding>> segmentBindings = new HashMap<>();
        try {
            bindings = this.callbackRegistry.resolve(emote.model().callbacks());
            for (var segment : emote.playbackSegments()) {
                segmentBindings.computeIfAbsent(segment.animation(), animation -> this.callbackRegistry.resolve(animation.model().callbacks()));
            }
        } catch (IllegalArgumentException exception) {
            return new StartResult.Failure(FailureReason.START_REJECTED, exception.getMessage());
        }
        if (replacedSession != null) stop(replacedSession, PlaybackStopReason.REPLACED);
        PlaybackNodes nodes = null;
        PlaybackSession session = null;
        try {
            nodes = this.entityController.create(request.level(), request.root(), emote);
            PlaybackPlayer timeline = new PlaybackPlayer(emote, new EntityTimelineTarget(emote, nodes, this.entityController), request.queries());
            timeline.bindEvents(new EventCommandExecutor(request.level(), request.commandSource(), nodes, timeline));
            if (emote.model().settings().playback().mode() == EmoteAnimation.LoopMode.SERVER_SYNC) {
                timeline.startSynchronized(EmoteMod.SERVER.overworld().getGameTime());
            } else {
                timeline.start();
            }
            if (request.skin() != null) this.entityController.applySkin(nodes, emote.skinBindings(), request.skin());
            timeline.deferInitialVisibility();
            this.entityController.add(request.level(), nodes);
            session = new PlaybackSession(UUID.randomUUID(), request.level().dimension(), request.emoteId(),
                nodes, timeline, request.actors(), request.placementMode());
            session.bindCallbacks(bindings, segmentBindings, EmoteMod.SERVER.getTickCount());
            register(session, request.lifecycle());
            request.lifecycle().onStarted(session);
            if (contains(session)) notifyStarted(session);
            if (contains(session)) session.startPlayback();
            return new StartResult.Success(session);
        } catch (RuntimeException exception) {
            EmoteMod.LOGGER.warn("Failed to start emote {}", emote.id(), exception);
            if (session != null && contains(session)) {
                stop(session, PlaybackStopReason.ERROR);
            } else if (session == null && nodes != null) {
                this.entityController.remove(request.level(), nodes);
            }
            return new StartResult.Failure(FailureReason.START_REJECTED, "Something went wrong while starting the emote.");
        }
    }

    record ActivePlayback(PlaybackSession session, Lifecycle lifecycle, Lifecycle stateListener) {}

    void register(PlaybackSession session, Lifecycle lifecycle) {
        if (this.activePlaybacks.putIfAbsent(session.sessionId(), new ActivePlayback(session, lifecycle, Lifecycle.NONE)) != null) {
            throw new IllegalStateException("Playback session is already registered: " + session.sessionId());
        }
        this.activeDisplayEntities += session.nodes().displayEntityCount();
    }

    void notifyStarted(PlaybackSession session) {
        ActivePlayback playback = this.activePlaybacks.get(session.sessionId());
        this.activePlaybacks.put(session.sessionId(), new ActivePlayback(session, playback.lifecycle(), this.stateListener));
        this.stateListener.onStarted(session);
    }

    @Nullable ActivePlayback remove(PlaybackSession session) {
        ActivePlayback playback = this.activePlaybacks.get(session.sessionId());
        if (playback == null || playback.session() != session || !this.activePlaybacks.remove(session.sessionId(), playback)) return null;
        this.activeDisplayEntities -= session.nodes().displayEntityCount();
        return playback;
    }

    public @Nullable PlaybackSession findSession(UUID sessionId) {
        ActivePlayback playback = this.activePlaybacks.get(sessionId);
        return playback == null ? null : playback.session();
    }
    public boolean contains(PlaybackSession session) { return findSession(session.sessionId()) == session; }

    public @Nullable PlaybackSession stop(UUID sessionId, PlaybackStopReason reason) {
        PlaybackSession session = findSession(sessionId);
        return session == null ? null : stop(session, reason);
    }

    public boolean setPlacement(UUID sessionId, PlaybackPlacement placement) {
        ActivePlayback playback = this.activePlaybacks.get(sessionId);
        if (playback == null) return false;
        PlaybackSession session = playback.session();
        RootTransform root = placement.mode() == PlaybackPlacement.Mode.ACTOR
            ? playback.lifecycle().resolveActorPlacement(session) : RootTransform.create(placement.position(), placement.yaw());
        if (root == null) throw new IllegalArgumentException("Playback has no actor placement");
        this.entityController.moveSceneTo(session.nodes(), root.position());
        this.entityController.updateViewRotation(session.nodes(), root.yaw(), 0);
        session.setPlacementMode(placement.mode());
        return true;
    }

    public void tick() {
        this.stressTest.tick();
        for (ActivePlayback playback : List.copyOf(this.activePlaybacks.values())) {
            PlaybackSession session = playback.session();
            if (!contains(session)) continue;
            PlaybackStopReason reason = null;
            try {
                reason = playback.lifecycle().beforeTick(session);
                if (!contains(session)) continue;
                if (reason == null && session.tick(EmoteMod.SERVER.getTickCount())) {
                    session.beginFrame();
                    try {
                        playback.lifecycle().prepareFrame(session);
                        if (!contains(session)) continue;
                        session.playback().restoreDeferredVisibility();
                        session.playback().advance();
                        if (!contains(session)) continue;
                        session.tickCallbacks();
                        if (!contains(session)) continue;
                    } finally {
                        session.endFrame();
                    }
                    if (contains(session) && session.playback().isFinished()) reason = PlaybackStopReason.FINISHED;
                }
            } catch (RuntimeException exception) {
                EmoteMod.LOGGER.warn("Failed to play emote {}", session.emoteId(), exception);
                reason = PlaybackStopReason.ERROR;
            }
            if (reason != null) stop(session, reason);
        }
    }

    public @Nullable PlaybackSession stop(PlaybackSession session, PlaybackStopReason reason) {
        ActivePlayback playback = remove(session);
        if (playback == null || !session.beginClose(reason)) return null;
        Lifecycle lifecycle = playback.lifecycle();
        try { lifecycle.onClosing(session); }
        catch (RuntimeException exception) { EmoteMod.LOGGER.warn("Failed to notify emote {} closing", session.emoteId(), exception); }
        Runnable cleanup = () -> finishCleanup(session, lifecycle, playback.stateListener(), reason);
        if (!session.deferCleanup(cleanup)) cleanup.run();
        return session;
    }

    private void finishCleanup(PlaybackSession session, Lifecycle lifecycle, Lifecycle stateListener, PlaybackStopReason reason) {
        try {
            try { session.playback().stop(reason); }
            catch (RuntimeException exception) { EmoteMod.LOGGER.warn("Failed to run stop events for emote {}", session.emoteId(), exception); }
            session.closeCallbacks();
        } finally {
            try { lifecycle.onStopped(session, reason); }
            catch (RuntimeException exception) { EmoteMod.LOGGER.warn("Failed to notify emote {} stopped", session.emoteId(), exception); }
            finally {
                try {
                    try { stateListener.onStopped(session, reason); }
                    catch (RuntimeException exception) { EmoteMod.LOGGER.warn("Failed to notify emote {} API stop", session.emoteId(), exception); }
                    ServerLevel level = EmoteMod.SERVER.getLevel(session.levelKey());
                    if (level != null) this.entityController.remove(level, session.nodes());
                } finally { session.completeClose(); }
            }
        }
    }

    public void stopAll() { stopAll(PlaybackStopReason.MANUAL); }
    public void stopAll(PlaybackStopReason reason) {
        this.stressTest.stop();
        for (var playback : List.copyOf(this.activePlaybacks.values())) stop(playback.session(), reason);
    }
    public void stopById(String id) { stopById(id, PlaybackStopReason.EMOTE_REMOVED); }
    public void stopById(String id, PlaybackStopReason reason) {
        this.stressTest.stopById(id);
        for (var playback : List.copyOf(this.activePlaybacks.values())) {
            PlaybackSession session = playback.session();
            if (session.emoteId().equals(id)) stop(session, reason);
        }
    }
    public int activeDisplayEntityCount() { return this.stressTest.displayEntityCount() + this.activeDisplayEntities; }
    public int activeSessionCount() { return this.activePlaybacks.size(); }

    public PlaybackStressTest stressTest() { return this.stressTest; }

    static boolean exceedsDisplayEntityLimit(int projectedDisplayEntities, int limit) {
        return limit > 0 && projectedDisplayEntities > limit;
    }

    static int projectedDisplayEntityCount(int activeDisplayEntities, int replacedDisplayEntities, int requestedDisplayEntities) {
        return activeDisplayEntities - replacedDisplayEntities + requestedDisplayEntities;
    }
}
