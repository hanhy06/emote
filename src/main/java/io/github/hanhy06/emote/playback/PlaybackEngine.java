package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.config.Config;
import io.github.hanhy06.emote.config.ConfigListener;
import io.github.hanhy06.emote.content.PreparedAnimation;
import io.github.hanhy06.emote.playback.molang.MolangQuerySource;
import net.minecraft.world.entity.Entity;
import io.github.hanhy06.emote.playback.runtime.*;
import io.github.hanhy06.emote.playback.session.*;
import io.github.hanhy06.emote.playback.stress.*;
import io.github.hanhy06.emote.playback.timeline.EventCommandExecutor;
import io.github.hanhy06.emote.skin.model.PreparedPlayerSkin;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import java.util.*;
import java.util.function.Consumer;

public final class PlaybackEngine implements ConfigListener {
    public static final int DEFAULT_STRESS_TEST_INSTANCE_COUNT = PlaybackStressTest.DEFAULT_INSTANCE_COUNT;
    public static final int MAX_STRESS_TEST_INSTANCE_COUNT = PlaybackStressTest.MAX_INSTANCE_COUNT;
    public static final int DEFAULT_STRESS_TEST_PACKET_FANOUT = PlaybackStressTest.DEFAULT_PACKET_FANOUT;
    public static final int MAX_STRESS_TEST_PACKET_FANOUT = PlaybackStressTest.MAX_PACKET_FANOUT;
    private final PlaybackSessionRegistry sessionRegistry = new PlaybackSessionRegistry();
    private final PlaybackEntityController entityController = new PlaybackEntityController();
    private final PlaybackStressTest stressTest = new PlaybackStressTest(this.entityController);
    private final CallbackRegistry callbackRegistry = new CallbackRegistry();
    private final Map<UUID, Lifecycle> lifecycles = new HashMap<>();
    private int maxActiveDisplayEntities = Config.DEFAULT_MAX_ACTIVE_DISPLAY_ENTITIES;

    public interface Lifecycle {
        Lifecycle NONE = new Lifecycle() {};
        default void onStarted(PlaybackSession session) {}
        default @Nullable PlaybackStopReason beforeTick(PlaybackSession session) { return null; }
        default void prepareFrame(PlaybackSession session) {}
        default void onClosing(PlaybackSession session) {}
        default void onStopped(PlaybackSession session, PlaybackStopReason reason) {}
    }

    public record Request(ServerLevel level, RootTransform root, PreparedAnimation animation, String playbackId,
                          Map<String, Entity> actors, MolangQuerySource queries, @Nullable CommandSourceStack commandSource,
                          @Nullable PreparedPlayerSkin skin, Lifecycle lifecycle) {
        public Request {
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(animation, "animation");
            Objects.requireNonNull(playbackId, "playbackId");
            actors = Map.copyOf(actors);
            Objects.requireNonNull(queries, "queries");
            Objects.requireNonNull(lifecycle, "lifecycle");
        }
        public Request(ServerLevel level, RootTransform root, PreparedAnimation animation) {
            this(level, root, animation, animation.id(), Map.of(), MolangQuerySource.EMPTY, null, null, Lifecycle.NONE);
        }
    }

    public sealed interface StartResult {
        record Success(PlaybackSession session) implements StartResult {}
        record Failure(String message) implements StartResult {}
    }

    public CallbackRegistry callbackRegistry() { return this.callbackRegistry; }
    public PlaybackEntityController entities() { return this.entityController; }
    @Override public void onConfigReload(Config config) { this.maxActiveDisplayEntities = config.maxActiveDisplayEntities(); }

    public StartResult start(Request request, @Nullable PlaybackSession replacedSession) {
        PreparedAnimation emote = request.animation();
        if (replacedSession != null && !contains(replacedSession)) {
            return new StartResult.Failure("The playback being replaced is no longer active.");
        }
        if (replacedSession != null && replacedSession.isInvokingCallback()) {
            return new StartResult.Failure("Cannot replace an emote from its own callback.");
        }
        int projected = projectedDisplayEntityCount(activeDisplayEntityCount(),
            replacedSession == null ? 0 : replacedSession.nodes().displayEntityCount(), emote.displayNodeCount());
        if (exceedsDisplayEntityLimit(projected, this.maxActiveDisplayEntities)) {
            return new StartResult.Failure("Too many emotes are active right now. Try again shortly.");
        }
        List<CallbackRegistry.Binding> bindings;
        Map<PreparedAnimation, List<CallbackRegistry.Binding>> segmentBindings = new HashMap<>();
        try {
            bindings = this.callbackRegistry.resolve(emote.animation().callbacks());
            for (var segment : emote.playbackSegments()) {
                segmentBindings.computeIfAbsent(segment.animation(), animation -> this.callbackRegistry.resolve(animation.animation().callbacks()));
            }
        } catch (IllegalArgumentException exception) {
            return new StartResult.Failure(exception.getMessage());
        }
        if (replacedSession != null) stop(replacedSession, PlaybackStopReason.REPLACED);
        PlaybackNodes nodes = null;
        PlaybackSession session = null;
        try {
            nodes = this.entityController.create(request.level(), request.root(), emote);
            AnimationPlayer timeline = new AnimationPlayer(emote, new EntityTimelineTarget(emote, nodes, this.entityController), request.queries());
            timeline.bindEvents(new EventCommandExecutor(request.level(), request.commandSource(), nodes, timeline));
            if (emote.animation().settings().playback().mode() == EmoteAnimation.LoopMode.SERVER_SYNC) {
                timeline.startSynchronized(EmoteMod.SERVER.overworld().getGameTime());
            } else {
                timeline.start();
            }
            if (request.skin() != null) this.entityController.applySkin(nodes, emote.skinBindings(), request.skin());
            timeline.deferInitialVisibility();
            this.entityController.add(request.level(), nodes);
            session = new PlaybackSession(UUID.randomUUID(), request.level().dimension(), request.playbackId(), emote.id(),
                nodes, timeline, request.actors());
            session.bindCallbacks(bindings, segmentBindings, EmoteMod.SERVER.getTickCount());
            this.sessionRegistry.register(session);
            this.lifecycles.put(session.sessionId(), request.lifecycle());
            request.lifecycle().onStarted(session);
            if (this.sessionRegistry.contains(session)) session.startPlayback();
            return new StartResult.Success(session);
        } catch (RuntimeException exception) {
            EmoteMod.LOGGER.warn("Failed to start emote {}", emote.id(), exception);
            if (session != null && this.sessionRegistry.contains(session)) {
                stop(session, PlaybackStopReason.ERROR);
            } else if (session == null && nodes != null) {
                this.entityController.remove(request.level(), nodes);
            }
            return new StartResult.Failure("Something went wrong while starting the emote.");
        }
    }

    public @Nullable PlaybackSession findSession(UUID sessionId) { return this.sessionRegistry.findSession(sessionId); }
    public boolean contains(PlaybackSession session) { return this.sessionRegistry.contains(session); }

    public void tick() {
        this.stressTest.tick();
        for (PlaybackSession session : List.copyOf(this.sessionRegistry.sessions())) {
            if (!contains(session)) continue;
            PlaybackStopReason reason = null;
            try {
                reason = this.lifecycles.get(session.sessionId()).beforeTick(session);
                if (!contains(session)) continue;
                if (reason == null && session.tick(EmoteMod.SERVER.getTickCount())) {
                    this.lifecycles.get(session.sessionId()).prepareFrame(session);
                    if (!contains(session)) continue;
                    session.animation().restoreDeferredVisibility();
                    var result = session.animation().advance();
                    if (!contains(session)) continue;
                    session.tickCallbacks();
                    if (!contains(session)) continue;
                    if (result == AnimationPlayer.AdvanceResult.FINISHED) reason = PlaybackStopReason.FINISHED;
                }
            } catch (RuntimeException exception) {
                EmoteMod.LOGGER.warn("Failed to play emote {}", session.id(), exception);
                reason = PlaybackStopReason.ERROR;
            }
            if (reason != null) stop(session, reason);
        }
    }

    public @Nullable PlaybackSession stop(PlaybackSession session, PlaybackStopReason reason) {
        if (!this.sessionRegistry.remove(session)) return null;
        if (!session.beginClose(reason)) return null;
        Lifecycle lifecycle = this.lifecycles.remove(session.sessionId());
        try { lifecycle.onClosing(session); }
        catch (RuntimeException exception) { EmoteMod.LOGGER.warn("Failed to notify emote {} closing", session.id(), exception); }
        Runnable cleanup = () -> finishCleanup(session, lifecycle, reason);
        if (!session.deferCleanup(cleanup)) cleanup.run();
        return session;
    }

    private void finishCleanup(PlaybackSession session, Lifecycle lifecycle, PlaybackStopReason reason) {
        try {
            try { session.animation().stop(reason); }
            catch (RuntimeException exception) { EmoteMod.LOGGER.warn("Failed to run stop events for emote {}", session.id(), exception); }
            session.closeCallbacks();
        } finally {
            try { lifecycle.onStopped(session, reason); }
            catch (RuntimeException exception) { EmoteMod.LOGGER.warn("Failed to notify emote {} stopped", session.id(), exception); }
            finally {
                try {
                    ServerLevel level = EmoteMod.SERVER.getLevel(session.levelKey());
                    if (level != null) this.entityController.remove(level, session.nodes());
                } finally { session.completeClose(); }
            }
        }
    }

    public void stopAll() { stopAll(PlaybackStopReason.MANUAL); }
    public void stopAll(PlaybackStopReason reason) {
        this.stressTest.stop();
        for (var session : List.copyOf(this.sessionRegistry.sessions())) stop(session, reason);
    }
    public void stopById(String id) { stopById(id, PlaybackStopReason.EMOTE_REMOVED); }
    public void stopById(String id, PlaybackStopReason reason) {
        this.stressTest.stopById(id);
        for (var session : List.copyOf(this.sessionRegistry.sessions())) {
            if (session.id().equals(id) || session.animationId().equals(id)) stop(session, reason);
        }
    }
    public int activeDisplayEntityCount() { return this.stressTest.displayEntityCount() + this.sessionRegistry.activeDisplayEntityCount(); }
    public int activeSessionCount() { return this.sessionRegistry.activeSessionCount(); }

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

    public @Nullable PlaybackStressTestReport stopStressTest() {
        return this.stressTest.stop();
    }

    static boolean exceedsDisplayEntityLimit(int projectedDisplayEntities, int limit) {
        return limit > 0 && projectedDisplayEntities > limit;
    }

    static int projectedDisplayEntityCount(int activeDisplayEntities, int replacedDisplayEntities, int requestedDisplayEntities) {
        return activeDisplayEntities - replacedDisplayEntities + requestedDisplayEntities;
    }
}
