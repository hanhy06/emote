package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.PlaybackPlacement;
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
    private final Map<UUID, PlaybackSession> activePlaybacks = new HashMap<>();
    private final List<PlaybackSession> closingPlaybacks = new ArrayList<>();
    private final CallbackRegistry callbackRegistry = new CallbackRegistry();
    private int activeDisplayEntities;
    private int maxActiveDisplayEntities = Config.DEFAULT_MAX_ACTIVE_DISPLAY_ENTITIES;
    private PlaybackStressTest stressTest;
    private ExecutorService poseExecutor;
    private List<PlaybackSession> framePlaybacks = List.of();
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
                          @Nullable Map<String, Map<PlayerSkinRegion, String>> skin, Lifecycle lifecycle, PlaybackPlacement.Mode placementMode) {
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
            for (PlaybackSession session : this.framePlaybacks) session.discardFrame();
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
        int projected = activeDisplayEntityCount() - (replacedSession == null ? 0 : replacedSession.scene().displayEntityCount()) + emote.displayEntityCount();
        if (this.maxActiveDisplayEntities > 0 && projected > this.maxActiveDisplayEntities) {
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
            if (request.skin() != null) scene.setSkins(request.skin());
            session = new PlaybackSession(this, UUID.randomUUID(), request.level().dimension(), scene, playback, request.actors(), request.placementMode(), request.lifecycle());
            session.bindCallbacks(bindings, animationBindings);
            if (replacedSession != null) stop(replacedSession, PlaybackStopReason.REPLACED);
            register(session);
            return new StartResult.Success(session);
        } catch (RuntimeException exception) {
            EmoteMod.LOGGER.warn("Failed to register emote {}", emote.id(), exception);
            if (session != null && contains(session)) stop(session, PlaybackStopReason.ERROR);
            else if (scene != null) scene.close();
            return new StartResult.Failure(FailureReason.START_REJECTED, "Something went wrong while starting the emote.");
        }
    }

    private void register(PlaybackSession session) {
        if (this.activePlaybacks.putIfAbsent(session.sessionId(), session) != null) {
            throw new IllegalStateException("Playback session is already registered: " + session.sessionId());
        }
        this.activeDisplayEntities += session.scene().displayEntityCount();
        session.registered(this.stateListener);
    }

    public @Nullable PlaybackSession findSession(UUID sessionId) {
        return this.activePlaybacks.get(sessionId);
    }
    public boolean contains(PlaybackSession session) { return findSession(session.sessionId()) == session; }

    public @Nullable PlaybackSession stop(UUID sessionId, PlaybackStopReason reason) {
        PlaybackSession session = findSession(sessionId);
        return session == null ? null : stop(session, reason);
    }

    public void beginTick() {
        if (this.frameTask != null || !this.framePlaybacks.isEmpty()) throw new IllegalStateException("Previous emote tick has not finished");
        List<PlaybackSession> frames = new ArrayList<>();
        boolean hasStressFrames = this.stressTest.prepareTick();
        for (PlaybackSession session : List.copyOf(this.activePlaybacks.values())) {
            if (!contains(session)) continue;
            if (session.prepareFrame(EmoteMod.SERVER.getTickCount(), EmoteMod.SERVER.overworld().getGameTime())) frames.add(session);
        }
        this.framePlaybacks = List.copyOf(frames);
        if (!frames.isEmpty() || hasStressFrames) {
            try {
                this.frameTask = this.poseExecutor.submit(() -> {
                    for (PlaybackSession session : frames) session.calculateFrame();
                    this.stressTest.calculateFrames();
                });
            } catch (RuntimeException exception) {
                for (PlaybackSession session : frames) session.failFrame(exception);
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
            for (PlaybackSession session : this.framePlaybacks) session.failFrame(exception);
            this.stressTest.failFrames(exception);
        }
        for (PlaybackSession session : this.framePlaybacks) session.applyFrame();
        this.stressTest.applyFrames();
        finishClosings();
        for (PlaybackSession session : this.framePlaybacks) session.dispatchFrame();
        this.framePlaybacks = List.of();
        finishClosings();
        this.stressTest.tick(endTickStartedNanos);
    }

    public @Nullable PlaybackSession stop(PlaybackSession session, PlaybackStopReason reason) {
        if (!contains(session) || !session.beginClose(reason)) return null;
        this.activePlaybacks.remove(session.sessionId());
        this.activeDisplayEntities -= session.scene().displayEntityCount();
        this.closingPlaybacks.add(session);
        session.notifyClosing();
        return session;
    }

    private void finishClosings() {
        while (!this.closingPlaybacks.isEmpty()) {
            List<PlaybackSession> closing = List.copyOf(this.closingPlaybacks);
            this.closingPlaybacks.clear();
            for (PlaybackSession session : closing) session.finishClose();
        }
    }

    public void stopAll() { stopAll(PlaybackStopReason.MANUAL); }
    public void stopAll(PlaybackStopReason reason) {
        this.stressTest.stop();
        for (PlaybackSession session : List.copyOf(this.activePlaybacks.values())) stop(session, reason);
    }
    public void stopById(String id) { stopById(id, PlaybackStopReason.EMOTE_REMOVED); }
    public void stopById(String id, PlaybackStopReason reason) {
        this.stressTest.stopById(id);
        for (PlaybackSession session : List.copyOf(this.activePlaybacks.values())) {
            if (session.emoteId().equals(id)) stop(session, reason);
        }
    }
    public int activeDisplayEntityCount() { return this.stressTest.displayEntityCount() + this.activeDisplayEntities; }
    public int activeSessionCount() { return this.activePlaybacks.size(); }
    public PlaybackStressTest stressTest() { return this.stressTest; }
}
