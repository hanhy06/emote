package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.*;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.content.PreparedAnimation;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class PlaybackSession implements EmotePlayback.LifecycleListener {
    private final PlaybackEngine engine;
    private final PlaybackEngine.Lifecycle lifecycle;
    private PlaybackEngine.Lifecycle stateListener;
    private final EmoteInfo emoteInfo;
    private final View view = new View();
    private final UUID sessionId;
    private List<Context> callbackContexts = List.of();
    private List<Context> animationCallbackContexts = List.of();
    private Map<PreparedAnimation, List<CallbackRegistry.Binding>> animationBindings = Map.of();
    private PlaybackState state = PlaybackState.PENDING;
    private @Nullable PlaybackStopReason stopReason;
    private long elapsedTicks;
    private long lastServerTick = Long.MIN_VALUE;
    private boolean callbacksStarted;
    private boolean callbacksClosed;
    private boolean initialized;
    private @Nullable Supplier<EmotePlayback.Frame> pendingSeek;
    private @Nullable PlaybackPlacement pendingPlacement;
    private EmotePlayback.@Nullable PoseView appliedPose;
    private EmotePlayback.@Nullable Frame frame;
    private @Nullable RuntimeException frameError;
    private boolean firstFrame, frameApplied, apiStarted;
    private PlaybackPlacement.Mode appliedPlacementMode;
    private final ResourceKey<Level> levelKey;
    private final PlaybackScene scene;
    private final EmotePlayback playback;
    private final Map<String, Entity> actors;
    private PlaybackPlacement.Mode placementMode;

    public PlaybackSession(
        PlaybackEngine engine,
        UUID sessionId,
        ResourceKey<Level> levelKey,
        PlaybackScene scene,
        EmotePlayback playback,
        Map<String, Entity> actors,
        PlaybackPlacement.Mode placementMode,
        PlaybackEngine.Lifecycle lifecycle
    ) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.levelKey = Objects.requireNonNull(levelKey, "levelKey");
        this.scene = Objects.requireNonNull(scene, "scene");
        this.playback = Objects.requireNonNull(playback, "playback");
        this.emoteInfo = playback.emote().info();
        this.actors = Map.copyOf(actors);
        this.placementMode = Objects.requireNonNull(placementMode, "placementMode");
        this.appliedPlacementMode = placementMode;
    }

    public UUID sessionId() {
        return this.sessionId;
    }

    public PlaybackState state() {
        return this.state;
    }

    public PlaybackStopReason stopReason() { return Objects.requireNonNull(this.stopReason); }

    public Playback view() { return this.view; }

    private PlaybackPlacement placement(EmotePlayback.@Nullable PoseView pose) {
        return new PlaybackPlacement(this.appliedPlacementMode, pose == null ? this.scene.root().position() : pose.root().position(),
            pose == null ? this.scene.orientationYaw() : pose.orientationYaw());
    }

    public boolean isActive() { return this.state == PlaybackState.PENDING || this.state == PlaybackState.RUNNING; }

    void registered(PlaybackEngine.Lifecycle stateListener) {
        this.stateListener = stateListener;
        this.lifecycle.onRegistered(this);
    }

    private boolean setPlacement(PlaybackPlacement placement) {
        if (!isActive()) return false;
        if (placement.mode() == PlaybackPlacement.Mode.ACTOR && this.lifecycle.resolveActorPlacement(this) == null) {
            throw new IllegalArgumentException("Playback has no actor placement");
        }
        this.pendingPlacement = placement;
        return true;
    }

    public void bindCallbacks(
        List<CallbackRegistry.Binding> bindings,
        Map<PreparedAnimation, List<CallbackRegistry.Binding>> animationBindings
    ) {
        this.callbackContexts = bindings.stream().map(binding -> new Context(binding, this.emoteInfo.id())).toList();
        this.animationBindings = Map.copyOf(animationBindings);
        this.playback.bindLifecycleListener(this);
    }

    private void start() {
        if (this.callbacksStarted) throw new IllegalStateException("Callbacks already started.");
        if (this.state != PlaybackState.RUNNING) return;
        this.callbacksStarted = true;
        for (Context context : this.callbackContexts) {
            context.started = true;
            context.dispatch(context.binding.callbacks()::onStart);
            if (this.state != PlaybackState.RUNNING) break;
        }
    }

    private boolean seekTimeline(int timelineTick) {
        if (this.state != PlaybackState.RUNNING) return false;
        if (timelineTick < 0) throw new IllegalArgumentException("Tick must be non-negative");
        if (!this.playback.canSeekTimeline(timelineTick)) return false;
        this.pendingSeek = () -> this.playback.prepareSeekTimeline(timelineTick);
        return true;
    }

    private boolean queueAnimationSeek(int animationTick, EmotePlayback.@Nullable PoseView pose) {
        if (this.state != PlaybackState.RUNNING || pose == null || pose.cursor().phase() != PlaybackTimeline.Phase.ANIMATION || !this.playback.canSeek()) return false;
        if (animationTick < 0 || animationTick > this.playback.animationDurationTicks(pose.cursor())) throw new IllegalArgumentException("Tick is outside the animation");
        int segment = pose.cursor().segmentIndex();
        this.pendingSeek = () -> this.playback.cursor().segmentIndex() == segment ? this.playback.prepareSeekAnimation(animationTick) : null;
        return true;
    }

    private boolean seekStep(int stepIndex, int repeatIndex, int animationTick) {
        if (this.state != PlaybackState.RUNNING) return false;
        this.playback.stepSegment(stepIndex, repeatIndex, animationTick);
        if (!this.playback.canSeek()) return false;
        this.pendingSeek = () -> this.playback.prepareSeekStep(stepIndex, repeatIndex, animationTick);
        return true;
    }

    boolean prepareFrame(long serverTick, long worldTick) {
        if (!isActive() || this.lastServerTick == serverTick) return false;
        this.frameError = null;
        this.frameApplied = false;
        this.firstFrame = this.state == PlaybackState.PENDING;
        try {
            PlaybackStopReason reason = this.lifecycle.beforeTick(this);
            if (!isActive()) return false;
            if (reason != null) { this.engine.stop(this, reason); return false; }
            PlaybackPlacement requested = this.pendingPlacement;
            this.pendingPlacement = null;
            if (requested != null) this.placementMode = requested.mode();
            RootTransform placement = this.placementMode == PlaybackPlacement.Mode.ACTOR
                ? this.lifecycle.resolveActorPlacement(this)
                : requested == null ? RootTransform.create(this.scene.root().position(), this.scene.viewYaw())
                    : RootTransform.create(requested.position(), requested.yaw());
            if (placement == null) throw new IllegalArgumentException("Playback has no actor placement");
            float deadzone = requested == null && this.placementMode == PlaybackPlacement.Mode.ACTOR ? this.lifecycle.rotationDeadzone(this) : 0;
            this.scene.preparePlacement(placement, deadzone);
            this.lastServerTick = serverTick;
            if (!this.initialized) {
                this.frame = this.playback.playbackMode() == EmoteAnimation.PlaybackMode.SERVER_SYNC
                    ? this.playback.prepareStartSynchronized(this.scene, worldTick) : this.playback.prepareStart(this.scene);
                this.initialized = true;
            } else {
                Supplier<EmotePlayback.Frame> seek = this.pendingSeek;
                this.pendingSeek = null;
                this.frame = seek == null ? null : seek.get();
                if (this.frame == null) this.frame = this.playback.prepareAdvance();
            }
            return true;
        } catch (RuntimeException exception) {
            EmoteMod.LOGGER.warn("Failed to prepare emote {}", emoteId(), exception);
            this.engine.stop(this, PlaybackStopReason.ERROR);
            return false;
        }
    }

    void calculateFrame() {
        try { this.frame.calculate(); }
        catch (RuntimeException exception) { this.frameError = exception; }
    }

    void failFrame(RuntimeException exception) { this.frameError = exception; }

    void discardFrame() {
        if (this.frame != null && !this.frameApplied) this.frame.discard();
        this.frame = null;
    }

    void applyFrame() {
        if (!isActive() || this.frameError != null) {
            discardFrame();
            if (this.frameError != null && isActive()) {
                EmoteMod.LOGGER.warn("Failed to calculate emote {}", emoteId(), this.frameError);
                this.engine.stop(this, PlaybackStopReason.ERROR);
            }
            return;
        }
        try {
            this.scene.applyPlacement();
            this.frame.apply();
            this.frameApplied = true;
            this.scene.applyAnimationSkin(this.frame.skinAnimationId());
            if (this.appliedPose != null) this.elapsedTicks++;
            this.appliedPose = this.frame.pose();
            this.appliedPlacementMode = this.placementMode;
            this.state = PlaybackState.RUNNING;
            if (this.firstFrame) this.scene.add();
        } catch (RuntimeException exception) {
            EmoteMod.LOGGER.warn("Failed to apply emote {}", emoteId(), exception);
            discardFrame();
            this.engine.stop(this, PlaybackStopReason.ERROR);
        }
    }

    void dispatchFrame() {
        if (this.frame == null || !this.frameApplied || !isActive()) return;
        try {
            if (this.firstFrame) {
                this.lifecycle.onStarted(this);
                if (!isActive()) return;
                this.apiStarted = true;
                this.stateListener.onStarted(this);
                if (!isActive()) return;
                start();
            }
            if (!isActive()) return;
            this.lifecycle.onFrameApplied(this);
            if (!isActive()) return;
            this.frame.dispatchEvents();
            if (!isActive()) return;
            if (!this.firstFrame) tickCallbacks();
            if (isActive() && this.frame.result() == EmotePlayback.AdvanceResult.FINISHED) this.engine.stop(this, PlaybackStopReason.FINISHED);
        } catch (RuntimeException exception) {
            EmoteMod.LOGGER.warn("Failed to dispatch emote {}", emoteId(), exception);
            this.engine.stop(this, PlaybackStopReason.ERROR);
        } finally { this.frame = null; }
    }

    private void stopPlayback(PlaybackStopReason reason) {
        if (!this.initialized) return;
        EmotePlayback.Frame frame = this.playback.prepareStop(reason);
        frame.apply();
        frame.dispatchEvents();
    }

    @Override
    public void onStart(PreparedAnimation animation) {
        this.animationCallbackContexts = this.animationBindings.getOrDefault(animation, List.of()).stream()
            .map(binding -> new Context(binding, animation.model().id())).toList();
        if (!this.callbacksStarted) return;
        for (Context context : this.animationCallbackContexts) {
            if (this.state != PlaybackState.RUNNING) break;
            context.started = true;
            context.dispatch(context.binding.callbacks()::onStart);
        }
    }

    @Override
    public void onTick(int animationTick) {
        for (Context context : this.animationCallbackContexts) {
            if (this.state != PlaybackState.RUNNING) break;
            context.dispatch(context.binding.callbacks()::onTick);
        }
    }

    @Override
    public void onLoop() {
        if (!this.callbacksStarted || this.state != PlaybackState.RUNNING) return;
        for (Context context : this.callbackContexts) {
            context.dispatch(context.binding.callbacks()::onLoop);
            if (this.state != PlaybackState.RUNNING) break;
        }
    }

    @Override
    public void onClose(PlaybackStopReason reason) {
        List<Context> contexts = this.animationCallbackContexts;
        this.animationCallbackContexts = List.of();
        for (Context context : contexts) {
            context.closeReason = reason;
            closeCallback(context);
        }
    }

    private void tickCallbacks() {
        if (!this.callbacksStarted || this.state != PlaybackState.RUNNING) return;
        for (Context context : this.callbackContexts) {
            context.dispatch(context.binding.callbacks()::onTick);
            if (this.state != PlaybackState.RUNNING) break;
        }
    }

    boolean beginClose(PlaybackStopReason reason) {
        if (!isActive()) return false;
        this.state = PlaybackState.CLOSING;
        this.pendingSeek = null;
        this.pendingPlacement = null;
        if (this.frame != null) this.frame.cancelEvents();
        this.stopReason = Objects.requireNonNull(reason, "reason");
        return true;
    }

    void notifyClosing() {
        try {
            for (String id : this.scene.nodes().keySet()) this.scene.setVisible(id, false);
            this.lifecycle.onClosing(this);
        } catch (RuntimeException exception) { EmoteMod.LOGGER.warn("Failed to close emote {}", emoteId(), exception); }
    }

    private void closeCallbacks() {
        if (this.state != PlaybackState.CLOSING || this.callbacksClosed) return;
        this.callbacksClosed = true;
        onClose(this.stopReason);
        for (Context context : this.callbackContexts) {
            context.closeReason = this.stopReason;
            closeCallback(context);
        }
    }

    private void closeCallback(Context context) {
        context.endTick = this.elapsedTicks;
        if (!context.started) return;
        try {
            context.dispatch(context.binding.callbacks()::onClose);
        } catch (RuntimeException exception) {
            EmoteMod.LOGGER.warn("Emote close callback failed for {}", this.emoteInfo.id(), exception);
        } finally { context.userState = null; }
    }

    void finishClose() {
        PlaybackStopReason reason = stopReason();
        try {
            discardFrame();
            try { stopPlayback(reason); }
            catch (RuntimeException exception) { EmoteMod.LOGGER.warn("Failed to run stop events for emote {}", emoteId(), exception); }
            closeCallbacks();
        } finally {
            try { this.lifecycle.onStopped(this, reason); }
            catch (RuntimeException exception) { EmoteMod.LOGGER.warn("Failed to notify emote {} stopped", emoteId(), exception); }
            finally {
                try {
                    if (this.apiStarted) {
                        try { this.stateListener.onStopped(this, reason); }
                        catch (RuntimeException exception) { EmoteMod.LOGGER.warn("Failed to notify emote {} API stop", emoteId(), exception); }
                    }
                    this.scene.close();
                } finally { this.state = PlaybackState.CLOSED; }
            }
        }
    }

    private class View implements Playback {
        protected EmotePlayback.@Nullable PoseView pose() { return PlaybackSession.this.appliedPose; }

        protected void requireServerThread() {
            if (!EmoteMod.SERVER.isSameThread()) throw new IllegalStateException("Playback access must run on the server thread.");
        }

        public UUID getSessionId() { return PlaybackSession.this.sessionId; }
        public Identifier getEmoteId() { return PlaybackSession.this.emoteInfo.id(); }
        public EmoteInfo getEmote() { return PlaybackSession.this.emoteInfo; }
        public MinecraftServer getServer() { return EmoteMod.SERVER; }
        public ServerLevel getWorld() {
            requireServerThread();
            return Objects.requireNonNull(getServer().getLevel(PlaybackSession.this.levelKey), "Playback level unavailable.");
        }
        public PlaybackState getState() { requireServerThread(); return PlaybackSession.this.state; }
        public boolean isActive() { requireServerThread(); return PlaybackSession.this.isActive(); }
        public long getElapsedTicks() { requireServerThread(); return PlaybackSession.this.elapsedTicks; }
        public @Nullable Integer getTimelineTick() { requireServerThread(); var pose = pose(); return pose == null ? null : Integer.valueOf(pose.timelineTick()); }
        public @Nullable PlaybackCursor getCursor() { requireServerThread(); var pose = pose(); return pose == null ? null : pose.cursor(); }
        public @Nullable Integer getAnimationTick() { var cursor = getCursor(); return cursor == null ? null : cursor.animationTick(); }
        public @Nullable PlaybackTimeline getTimeline() { requireServerThread(); var pose = pose(); return pose == null ? null : pose.timeline(); }
        public PlaybackPlacement getPlacement() { requireServerThread(); return placement(pose()); }
        public Vec3 getRootPosition() { return getPlacement().position(); }
        public Optional<PlaybackStopReason> getStopReason() { requireServerThread(); return Optional.ofNullable(PlaybackSession.this.stopReason); }
        public Optional<Entity> getActor() { return getActor("actor"); }

        public Optional<Entity> getActor(String name) {
            requireServerThread();
            Objects.requireNonNull(name, "name");
            return Optional.ofNullable(PlaybackSession.this.actors.get(name)).filter(entity -> !entity.isRemoved());
        }

        public Optional<Entity> getAttachmentEntity(String nodeId, String attachmentId) {
            requireServerThread();
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(attachmentId, "attachmentId");
            if (PlaybackSession.this.state == PlaybackState.CLOSED) return Optional.empty();
            return Optional.ofNullable(PlaybackSession.this.scene.nodes().get(nodeId))
                .map(node -> node.attachments().get(attachmentId))
                .flatMap(attachment -> Optional.<Entity>ofNullable(attachment.entity())).filter(entity -> !entity.isRemoved());
        }

        public Optional<Vec3> getNodeWorldPosition(String nodeId) {
            requireServerThread();
            Objects.requireNonNull(nodeId, "nodeId");
            var pose = pose();
            if (pose == null || !PlaybackSession.this.scene.nodes().containsKey(nodeId)) return Optional.empty();
            Vector3f point = pose.root().worldMatrix(pose.orientationYaw(), pose.nodeMatrix(nodeId)).transformPosition(new Vector3f());
            return Optional.of(pose.root().position().add(point.x, point.y, point.z));
        }

        public boolean stop() { return stop(PlaybackStopReason.MANUAL); }
        public boolean stop(PlaybackStopReason reason) {
            requireServerThread();
            return PlaybackSession.this.engine.stop(PlaybackSession.this, Objects.requireNonNull(reason, "reason")) != null;
        }
        public boolean seekTimeline(int timelineTick) { requireServerThread(); return PlaybackSession.this.seekTimeline(timelineTick); }
        public boolean seekAnimation(int animationTick) { requireServerThread(); return PlaybackSession.this.queueAnimationSeek(animationTick, pose()); }
        public boolean seekStep(int stepIndex, int repeatIndex, int animationTick) { requireServerThread(); return PlaybackSession.this.seekStep(stepIndex, repeatIndex, animationTick); }
        public boolean setPlacement(PlaybackPlacement placement) {
            requireServerThread();
            return PlaybackSession.this.setPlacement(Objects.requireNonNull(placement, "placement"));
        }
        public PlaybackInfo snapshot() {
            requireServerThread();
            var pose = pose();
            UUID playerUuid = PlaybackSession.this.actors.get("actor") instanceof ServerPlayer player ? player.getUUID() : null;
            return new PlaybackInfo(getSessionId(), playerUuid, getEmoteId(), PlaybackSession.this.state, PlaybackSession.this.elapsedTicks,
                pose == null ? null : Integer.valueOf(pose.timelineTick()), pose == null ? null : pose.cursor(), placement(pose));
        }
    }

    private final class Context extends View implements PlaybackContext {
        private final CallbackRegistry.Binding binding;
        private final Identifier scopeEmoteId;
        private final long startTick;
        private @Nullable Long endTick;
        private @Nullable PlaybackStopReason closeReason;
        private boolean started;
        private boolean dispatching;
        private @Nullable Object userState;

        private Context(CallbackRegistry.Binding binding, Identifier scopeEmoteId) {
            this.binding = binding;
            this.scopeEmoteId = scopeEmoteId;
            this.startTick = PlaybackSession.this.elapsedTicks;
        }

        @Override
        protected EmotePlayback.@Nullable PoseView pose() {
            var notification = this.dispatching ? PlaybackSession.this.playback.notificationPose() : null;
            return notification == null ? super.pose() : notification;
        }

        private void dispatch(Consumer<PlaybackContext> callback) {
            boolean previous = this.dispatching;
            this.dispatching = true;
            try { callback.accept(this); }
            finally { this.dispatching = previous; }
        }

        public String getPayload() { return this.binding.payload(); }
        public Identifier getScopeEmoteId() { return this.scopeEmoteId; }
        public long getScopeElapsedTicks() { requireServerThread(); return (this.endTick == null ? PlaybackSession.this.elapsedTicks : this.endTick) - this.startTick; }
        public Optional<PlaybackStopReason> getCloseReason() { requireServerThread(); return Optional.ofNullable(this.closeReason); }
        public @Nullable Object getUserState() { requireServerThread(); return this.userState; }
        public void setUserState(@Nullable Object state) { requireServerThread(); this.userState = state; }
    }

    public ResourceKey<Level> levelKey() {
        return this.levelKey;
    }

    public String emoteId() {
        return this.emoteInfo.id().toString();
    }

    public PlaybackScene scene() {
        return this.scene;
    }

    public EmotePlayback playback() {
        return this.playback;
    }
}
