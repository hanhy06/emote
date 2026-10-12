package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.PlaybackPlacement;
import io.github.hanhy06.emote.api.PlaybackState;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.config.Config;
import io.github.hanhy06.emote.config.ConfigListener;
import io.github.hanhy06.emote.content.PreparedAnimation;
import io.github.hanhy06.emote.content.PreparedEmote;
import io.github.hanhy06.emote.playback.molang.MolangQuerySource;
import io.github.hanhy06.emote.playback.stress.PlaybackStressTest;
import io.github.hanhy06.emote.skin.model.PlayerSkinRegion;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class PlaybackEngine implements ConfigListener {
    private final Map<UUID, ActivePlayback> activePlaybacks = new HashMap<>();
    private final List<ActivePlayback> closingPlaybacks = new ArrayList<>();
    private final CallbackRegistry callbackRegistry = new CallbackRegistry();
    private int activeDisplayEntities;
    private int maxActiveDisplayEntities = Config.DEFAULT_MAX_ACTIVE_DISPLAY_ENTITIES;
    private PlaybackStressTest stressTest;
    private ExecutorService poseExecutor;
    private List<ActivePlayback> framePlaybacks = List.of();
    private @Nullable Future<?> frameTask;
    private Lifecycle stateListener = Lifecycle.NONE;
    private boolean stopping;

    public interface Lifecycle {
        Lifecycle NONE = new Lifecycle() {};
        default void onRegistered(PlaybackSession session) {}
        default void onStarted(PlaybackSession session) {}
        default @Nullable PlaybackStopReason beforeTick(PlaybackSession session) { return null; }
        default @Nullable RootTransform resolveActorPlacement(PlaybackSession session) { return null; }
        default float rotationDeadzone(PlaybackSession session) { return session.playback().rotationDeadzone(); }
        default void onFrameApplied(PlaybackSession session) {}
        default void onClosing(PlaybackSession session) {}
        default void onStopped(PlaybackSession session, PlaybackStopReason reason) {}
    }

    public record Request(ServerLevel level, RootTransform root, PreparedEmote emote,
                          Map<String, Entity> actors, MolangQuerySource queries, @Nullable CommandSourceStack commandSource,
                          @Nullable Map<PlayerSkinRegion, String> skin, Lifecycle lifecycle, PlaybackPlacement.Mode placementMode) {
        public Request {
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(emote, "emote");
            actors = Map.copyOf(actors);
            Objects.requireNonNull(queries, "queries");
            Objects.requireNonNull(lifecycle, "lifecycle");
            Objects.requireNonNull(placementMode, "placementMode");
        }
        public Request(ServerLevel level, RootTransform root, PreparedEmote emote) {
            this(level, root, emote, Map.of(), MolangQuerySource.EMPTY, null, null, Lifecycle.NONE, PlaybackPlacement.Mode.EXTERNAL);
        }
    }

    public sealed interface StartResult {
        record Success(PlaybackSession session) implements StartResult {}
        record Failure(FailureReason reason, String message) implements StartResult {}
    }

    public enum FailureReason { DISPLAY_LIMIT, START_REJECTED }

    private static final class ActivePlayback {
        final PlaybackSession session;
        final Lifecycle lifecycle, stateListener;
        EmotePlayback.@Nullable Frame frame;
        @Nullable RuntimeException frameError;
        boolean firstFrame, frameApplied, apiStarted;

        ActivePlayback(PlaybackSession session, Lifecycle lifecycle, Lifecycle stateListener) {
            this.session = session;
            this.lifecycle = lifecycle;
            this.stateListener = stateListener;
        }
    }

    public CallbackRegistry callbackRegistry() { return this.callbackRegistry; }
    public void startWorker() {
        this.stopping = false;
        this.poseExecutor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("Emote pose worker").factory());
        this.stressTest = new PlaybackStressTest();
    }

    public void stopWorker() {
        this.stopping = true;
        try {
            try { awaitFrames(); }
            catch (RuntimeException exception) { EmoteMod.LOGGER.warn("Failed to finish emote frames during shutdown", exception); }
            for (ActivePlayback playback : this.framePlaybacks) {
                if (playback.frame != null && !playback.frameApplied) playback.frame.discard();
                playback.session.releaseFrame();
            }
            this.framePlaybacks = List.of();
            this.stressTest.stop();
            this.stressTest.discardFrames();
            finishClosings();
        } finally { this.poseExecutor.close(); }
    }

    public void setStateListener(Lifecycle listener) { this.stateListener = Objects.requireNonNull(listener, "listener"); }
    @Override public void onConfigReload(Config config) { this.maxActiveDisplayEntities = config.maxActiveDisplayEntities(); }

    public StartResult start(Request request, @Nullable PlaybackSession replacedSession) {
        if (this.stopping) return new StartResult.Failure(FailureReason.START_REJECTED, "The server is stopping.");
        PreparedEmote emote = request.emote();
        if (replacedSession != null && !contains(replacedSession)) {
            return new StartResult.Failure(FailureReason.START_REJECTED, "The playback being replaced is no longer active.");
        }
        int projected = projectedDisplayEntityCount(activeDisplayEntityCount(),
            replacedSession == null ? 0 : replacedSession.scene().displayEntityCount(), emote.displayEntityCount());
        if (exceedsDisplayEntityLimit(projected, this.maxActiveDisplayEntities)) {
            return new StartResult.Failure(FailureReason.DISPLAY_LIMIT, "Too many emotes are active right now. Try again shortly.");
        }
        PlaybackScene scene = null;
        PlaybackSession session = null;
        try {
            EmotePlayback playback = new EmotePlayback(emote, request.queries());
            List<CallbackRegistry.Binding> bindings = this.callbackRegistry.resolve(emote.callbacks());
            Map<PreparedAnimation, List<CallbackRegistry.Binding>> animationBindings = new HashMap<>();
            for (PreparedAnimation animation : playback.selectedAnimations()) animationBindings.put(animation, this.callbackRegistry.resolve(animation.callbacks()));
            scene = PlaybackScene.create(request.level(), request.root(), emote);
            playback.bindEvents(new EventCommandExecutor(request.level(), request.commandSource(), scene));
            if (request.skin() != null) scene.applySkin(emote.skinBindings(), request.skin());
            session = new PlaybackSession(UUID.randomUUID(), request.level().dimension(), emote.id(), scene, playback, request.actors(), request.placementMode());
            session.bindCallbacks(bindings, animationBindings);
            if (replacedSession != null) stop(replacedSession, PlaybackStopReason.REPLACED);
            register(session, request.lifecycle());
            request.lifecycle().onRegistered(session);
            return new StartResult.Success(session);
        } catch (RuntimeException exception) {
            EmoteMod.LOGGER.warn("Failed to register emote {}", emote.id(), exception);
            if (session != null && contains(session)) stop(session, PlaybackStopReason.ERROR);
            else if (scene != null) scene.close();
            return new StartResult.Failure(FailureReason.START_REJECTED, "Something went wrong while starting the emote.");
        }
    }

    void register(PlaybackSession session, Lifecycle lifecycle) {
        if (this.activePlaybacks.putIfAbsent(session.sessionId(), new ActivePlayback(session, lifecycle, this.stateListener)) != null) {
            throw new IllegalStateException("Playback session is already registered: " + session.sessionId());
        }
        this.activeDisplayEntities += session.scene().displayEntityCount();
    }

    public @Nullable PlaybackSession findSession(UUID sessionId) {
        ActivePlayback playback = this.activePlaybacks.get(sessionId);
        return playback == null ? null : playback.session;
    }
    public boolean contains(PlaybackSession session) { return findSession(session.sessionId()) == session; }

    public @Nullable PlaybackSession stop(UUID sessionId, PlaybackStopReason reason) {
        PlaybackSession session = findSession(sessionId);
        return session == null ? null : stop(session, reason);
    }

    public boolean setPlacement(UUID sessionId, PlaybackPlacement placement) {
        ActivePlayback playback = this.activePlaybacks.get(sessionId);
        if (playback == null) return false;
        if (placement.mode() == PlaybackPlacement.Mode.ACTOR && playback.lifecycle.resolveActorPlacement(playback.session) == null) {
            throw new IllegalArgumentException("Playback has no actor placement");
        }
        playback.session.queuePlacement(placement);
        return true;
    }

    public void beginTick() {
        if (this.frameTask != null || !this.framePlaybacks.isEmpty()) throw new IllegalStateException("Previous emote tick has not finished");
        List<ActivePlayback> frames = new ArrayList<>();
        boolean hasStressFrames = this.stressTest.prepareTick();
        for (ActivePlayback playback : List.copyOf(this.activePlaybacks.values())) {
            PlaybackSession session = playback.session;
            if (!contains(session)) continue;
            playback.frame = null;
            playback.frameError = null;
            playback.frameApplied = false;
            playback.firstFrame = session.state() == PlaybackState.PENDING;
            try {
                PlaybackStopReason reason = playback.lifecycle.beforeTick(session);
                if (!contains(session)) continue;
                if (reason != null) { stop(session, reason); continue; }
                PlaybackPlacement requested = session.takePlacement();
                RootTransform placement = session.placementMode() == PlaybackPlacement.Mode.ACTOR
                    ? playback.lifecycle.resolveActorPlacement(session)
                    : requested == null ? RootTransform.create(session.scene().root().position(), session.scene().viewYaw())
                        : RootTransform.create(requested.position(), requested.yaw());
                if (placement == null) throw new IllegalArgumentException("Playback has no actor placement");
                float deadzone = requested == null && session.placementMode() == PlaybackPlacement.Mode.ACTOR ? playback.lifecycle.rotationDeadzone(session) : 0;
                session.scene().preparePlacement(placement, deadzone);
                playback.frame = session.prepareFrame(EmoteMod.SERVER.getTickCount(), EmoteMod.SERVER.overworld().getGameTime());
                if (playback.frame != null) frames.add(playback);
            } catch (RuntimeException exception) {
                EmoteMod.LOGGER.warn("Failed to prepare emote {}", session.emoteId(), exception);
                stop(session, PlaybackStopReason.ERROR);
            }
        }
        this.framePlaybacks = List.copyOf(frames);
        if (!frames.isEmpty() || hasStressFrames) {
            try {
                this.frameTask = this.poseExecutor.submit(() -> {
                    for (ActivePlayback playback : frames) {
                        try { playback.frame.calculate(); }
                        catch (RuntimeException exception) { playback.frameError = exception; }
                    }
                    this.stressTest.calculateFrames();
                });
            } catch (RuntimeException exception) {
                for (ActivePlayback playback : frames) playback.frameError = exception;
                this.stressTest.failFrames(exception);
            }
        }
    }

    private void awaitFrames() {
        Future<?> task = this.frameTask;
        if (task == null) return;
        boolean interrupted = Thread.interrupted();
        try {
            while (true) {
                try { task.get(); break; }
                catch (InterruptedException exception) { interrupted = true; }
                catch (ExecutionException exception) { throw new IllegalStateException("Emote frame calculation failed", exception.getCause()); }
            }
        } finally {
            this.frameTask = null;
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (interrupted) throw new IllegalStateException("Interrupted while waiting for emote frames");
    }

    public void tick() {
        long endTickStartedNanos = System.nanoTime();
        try { awaitFrames(); }
        catch (RuntimeException exception) {
            for (ActivePlayback playback : this.framePlaybacks) playback.frameError = exception;
            this.stressTest.failFrames(exception);
        }
        long waitNanos = System.nanoTime() - endTickStartedNanos;
        for (ActivePlayback playback : this.framePlaybacks) {
            if (!contains(playback.session) || playback.frameError != null) {
                playback.frame.discard();
                playback.session.releaseFrame();
                if (playback.frameError != null && contains(playback.session)) {
                    EmoteMod.LOGGER.warn("Failed to calculate emote {}", playback.session.emoteId(), playback.frameError);
                    stop(playback.session, PlaybackStopReason.ERROR);
                }
                continue;
            }
            try {
                playback.session.applyFrame(playback.frame);
                playback.frameApplied = true;
                if (playback.firstFrame) playback.session.scene().add();
            } catch (RuntimeException exception) {
                EmoteMod.LOGGER.warn("Failed to apply emote {}", playback.session.emoteId(), exception);
                if (!playback.frameApplied) playback.frame.discard();
                playback.session.releaseFrame();
                stop(playback.session, PlaybackStopReason.ERROR);
            }
        }
        this.stressTest.applyFrames();
        finishClosings();
        for (ActivePlayback playback : this.framePlaybacks) {
            PlaybackSession session = playback.session;
            if (!playback.frameApplied || !contains(session)) continue;
            try {
                if (playback.firstFrame) {
                    playback.lifecycle.onStarted(session);
                    if (!contains(session)) continue;
                    playback.apiStarted = true;
                    playback.stateListener.onStarted(session);
                    if (!contains(session)) continue;
                    session.start();
                }
                if (!contains(session)) continue;
                playback.lifecycle.onFrameApplied(session);
                if (!contains(session)) continue;
                playback.frame.dispatchEvents();
                if (!contains(session)) continue;
                if (!playback.firstFrame) session.tickCallbacks();
                if (contains(session) && playback.frame.result() == EmotePlayback.AdvanceResult.FINISHED) stop(session, PlaybackStopReason.FINISHED);
            } catch (RuntimeException exception) {
                EmoteMod.LOGGER.warn("Failed to dispatch emote {}", session.emoteId(), exception);
                stop(session, PlaybackStopReason.ERROR);
            } finally { session.releaseFrame(); }
        }
        this.framePlaybacks = List.of();
        finishClosings();
        this.stressTest.tick(endTickStartedNanos, waitNanos);
    }

    public @Nullable PlaybackSession stop(PlaybackSession session, PlaybackStopReason reason) {
        ActivePlayback playback = this.activePlaybacks.get(session.sessionId());
        if (playback == null || playback.session != session || !session.beginClose(reason)) return null;
        this.activePlaybacks.remove(session.sessionId());
        this.activeDisplayEntities -= session.scene().displayEntityCount();
        this.closingPlaybacks.add(playback);
        try {
            for (String id : session.scene().nodes().keySet()) session.scene().setVisible(id, false);
            playback.lifecycle.onClosing(session);
        } catch (RuntimeException exception) { EmoteMod.LOGGER.warn("Failed to close emote {}", session.emoteId(), exception); }
        return session;
    }

    private void finishClosings() {
        while (!this.closingPlaybacks.isEmpty()) {
            List<ActivePlayback> closing = List.copyOf(this.closingPlaybacks);
            this.closingPlaybacks.clear();
            for (ActivePlayback playback : closing) finishCleanup(playback);
        }
    }

    private void finishCleanup(ActivePlayback playback) {
        PlaybackSession session = playback.session;
        PlaybackStopReason reason = session.stopReason();
        try {
            try { session.stopPlayback(reason); }
            catch (RuntimeException exception) { EmoteMod.LOGGER.warn("Failed to run stop events for emote {}", session.emoteId(), exception); }
            session.closeCallbacks();
        } finally {
            try { playback.lifecycle.onStopped(session, reason); }
            catch (RuntimeException exception) { EmoteMod.LOGGER.warn("Failed to notify emote {} stopped", session.emoteId(), exception); }
            finally {
                try {
                    if (playback.apiStarted) {
                        try { playback.stateListener.onStopped(session, reason); }
                        catch (RuntimeException exception) { EmoteMod.LOGGER.warn("Failed to notify emote {} API stop", session.emoteId(), exception); }
                    }
                    session.scene().close();
                } finally { session.completeClose(); }
            }
        }
    }

    public void stopAll() { stopAll(PlaybackStopReason.MANUAL); }
    public void stopAll(PlaybackStopReason reason) {
        this.stressTest.stop();
        for (ActivePlayback playback : List.copyOf(this.activePlaybacks.values())) stop(playback.session, reason);
    }
    public void stopById(String id) { stopById(id, PlaybackStopReason.EMOTE_REMOVED); }
    public void stopById(String id, PlaybackStopReason reason) {
        this.stressTest.stopById(id);
        for (ActivePlayback playback : List.copyOf(this.activePlaybacks.values())) {
            if (playback.session.emoteId().equals(id)) stop(playback.session, reason);
        }
    }
    public int activeDisplayEntityCount() { return this.stressTest.displayEntityCount() + this.activeDisplayEntities; }
    public int activeSessionCount() { return this.activePlaybacks.size(); }
    public PlaybackStressTest stressTest() { return this.stressTest; }

    static boolean exceedsDisplayEntityLimit(int projectedDisplayEntities, int limit) { return limit > 0 && projectedDisplayEntities > limit; }
    static int projectedDisplayEntityCount(int activeDisplayEntities, int replacedDisplayEntities, int requestedDisplayEntities) {
        return activeDisplayEntities - replacedDisplayEntities + requestedDisplayEntities;
    }
}
