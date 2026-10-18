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
import java.util.function.Supplier;

public final class PlaybackSession implements EmotePlayback.LifecycleListener {
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
    private @Nullable PlaybackTimeline appliedTimeline;
    private EmotePlayback.@Nullable Frame preparedFrame;
    private PlaybackPlacement.Mode appliedPlacementMode;
    private final ResourceKey<Level> levelKey;
    private final String emoteId;
    private final PlaybackScene scene;
    private final EmotePlayback playback;
    private final Map<String, Entity> actors;
    private PlaybackPlacement.Mode placementMode;

    public PlaybackSession(
        UUID sessionId,
        ResourceKey<Level> levelKey,
        String emoteId,
        PlaybackScene scene,
        EmotePlayback playback,
        Map<String, Entity> actors,
        PlaybackPlacement.Mode placementMode
    ) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.levelKey = Objects.requireNonNull(levelKey, "levelKey");
        this.emoteId = Objects.requireNonNull(emoteId, "emoteId");
        this.scene = Objects.requireNonNull(scene, "scene");
        this.playback = Objects.requireNonNull(playback, "playback");
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

    public PlaybackInfo info() {
        UUID playerUuid = this.actors.get("actor") instanceof ServerPlayer player ? player.getUUID() : null;
        return new PlaybackInfo(this.sessionId, playerUuid, Identifier.parse(this.emoteId), this.state,
            this.elapsedTicks, this.appliedPose == null ? null : this.appliedPose.tick(), this.appliedPose == null ? null : this.appliedPose.position(), placement());
    }

    public PlaybackPlacement placement() {
        return new PlaybackPlacement(this.appliedPlacementMode, this.appliedPose == null ? this.scene.root().position() : this.appliedPose.root().position(),
            this.appliedPose == null ? this.scene.orientationYaw() : this.appliedPose.orientationYaw());
    }

    public PlaybackPlacement.Mode placementMode() { return this.placementMode; }
    public @Nullable PlaybackTimeline timeline() { return this.appliedTimeline; }
    public boolean isActive() { return this.state == PlaybackState.PENDING || this.state == PlaybackState.RUNNING; }
    public void queuePlacement(PlaybackPlacement placement) { this.pendingPlacement = placement; }
    public @Nullable PlaybackPlacement takePlacement() {
        PlaybackPlacement placement = this.pendingPlacement;
        this.pendingPlacement = null;
        if (placement != null) this.placementMode = placement.mode();
        return placement;
    }

    public Optional<Vec3> nodeWorldPosition(String nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        return nodeWorldPosition(nodeId, this.appliedPose);
    }

    private Optional<Vec3> nodeWorldPosition(String nodeId, EmotePlayback.@Nullable PoseView pose) {
        if (pose == null || !this.scene.nodes().containsKey(nodeId)) return Optional.empty();
        Vector3f point = pose.root().worldMatrix(pose.orientationYaw(), pose.nodeMatrix(nodeId)).transformPosition(new Vector3f());
        return Optional.of(pose.root().position().add(point.x, point.y, point.z));
    }

    public void bindCallbacks(
        List<CallbackRegistry.Binding> bindings,
        Map<PreparedAnimation, List<CallbackRegistry.Binding>> animationBindings
    ) {
        this.callbackContexts = bindings.stream().map(binding -> new Context(binding, false)).toList();
        this.animationBindings = Map.copyOf(animationBindings);
        this.playback.bindLifecycleListener(this);
    }

    public void start() {
        if (this.callbacksStarted) throw new IllegalStateException("Callbacks already started.");
        if (this.state != PlaybackState.RUNNING) return;
        this.callbacksStarted = true;
        for (Context context : this.callbackContexts) {
            context.started = true;
            context.binding.callbacks().onStart(context);
            if (this.state != PlaybackState.RUNNING) break;
        }
    }

    public boolean setTick(int tick) {
        if (this.state != PlaybackState.RUNNING || !this.playback.canSetTick(tick)) return false;
        this.pendingSeek = () -> this.playback.prepareTick(tick);
        return true;
    }

    public boolean setAnimationTick(int animationTick) {
        return queueAnimationTick(animationTick, this.appliedPose);
    }

    private boolean queueAnimationTick(int animationTick, EmotePlayback.@Nullable PoseView pose) {
        if (this.state != PlaybackState.RUNNING || pose == null || pose.position().phase() != PlaybackTimeline.Phase.ANIMATION || !this.playback.canSeek()) return false;
        if (animationTick < 0 || animationTick > this.playback.animationDuration(pose.position())) throw new IllegalArgumentException("Tick is outside the animation");
        int segment = pose.position().segmentIndex();
        this.pendingSeek = () -> this.playback.position().segmentIndex() == segment ? this.playback.prepareAnimationTick(animationTick) : null;
        return true;
    }

    public boolean setStep(int stepIndex, int repeatIndex, int animationTick) {
        this.playback.stepSegment(stepIndex, repeatIndex, animationTick);
        if (!this.playback.canSeek() || this.state != PlaybackState.RUNNING) return false;
        this.pendingSeek = () -> this.playback.prepareStep(stepIndex, repeatIndex, animationTick);
        return true;
    }

    public EmotePlayback.@Nullable Frame prepareFrame(long serverTick, long worldTick) {
        if (!isActive() || this.lastServerTick == serverTick) return null;
        this.lastServerTick = serverTick;
        if (!this.initialized) {
            this.preparedFrame = this.playback.playbackMode() == EmoteAnimation.PlaybackMode.SERVER_SYNC
                ? this.playback.prepareStartSynchronized(this.scene, worldTick) : this.playback.prepareStart(this.scene);
            this.initialized = true;
            return this.preparedFrame;
        }
        Supplier<EmotePlayback.Frame> seek = this.pendingSeek;
        this.pendingSeek = null;
        EmotePlayback.Frame frame = seek == null ? null : seek.get();
        this.preparedFrame = frame == null ? this.playback.prepareAdvance() : frame;
        return this.preparedFrame;
    }

    public void applyFrame(EmotePlayback.Frame frame) {
        this.scene.applyPlacement();
        frame.apply();
        if (this.appliedPose != null) this.elapsedTicks++;
        this.appliedPose = frame.pose();
        this.appliedTimeline = frame.timeline();
        this.appliedPlacementMode = this.placementMode;
        this.state = PlaybackState.RUNNING;
    }

    public void releaseFrame() { this.preparedFrame = null; }

    public void stopPlayback(PlaybackStopReason reason) {
        if (!this.initialized) return;
        EmotePlayback.Frame frame = this.playback.prepareStop(reason);
        frame.apply();
        frame.dispatchEvents();
    }

    @Override
    public void onPositionChanged(int animationTick) {
        for (Context context : this.animationCallbackContexts) context.animationTick = animationTick;
    }

    private void startAnimationCallbacks() {
        for (Context context : this.animationCallbackContexts) {
            if (this.state != PlaybackState.RUNNING) break;
            context.started = true;
            context.binding.callbacks().onStart(context);
        }
    }

    @Override
    public void onStart(PreparedAnimation animation) {
        this.animationCallbackContexts = this.animationBindings.getOrDefault(animation, List.of()).stream()
            .map(binding -> new Context(binding, true)).toList();
        Integer animationTick = this.playback.position().animationTick();
        onPositionChanged(animationTick == null ? 0 : animationTick);
        if (this.callbacksStarted) startAnimationCallbacks();
    }

    @Override
    public void onTick(int animationTick) {
        onPositionChanged(animationTick);
        for (Context context : this.animationCallbackContexts) {
            if (this.state != PlaybackState.RUNNING) break;
            context.binding.callbacks().onTick(context);
        }
    }

    @Override
    public void onLoop() {
        loopCallbacks();
    }

    @Override
    public void onClose(PlaybackStopReason reason) {
        List<Context> contexts = this.animationCallbackContexts;
        this.animationCallbackContexts = List.of();
        for (Context context : contexts) {
            context.closeReason = reason;
            closeCallback(context);
            context.userState = null;
        }
    }

    public void tickCallbacks() {
        if (!this.callbacksStarted || this.state != PlaybackState.RUNNING) return;
        for (Context context : this.callbackContexts) {
            context.binding.callbacks().onTick(context);
            if (this.state != PlaybackState.RUNNING) break;
        }
    }

    private void loopCallbacks() {
        if (!this.callbacksStarted || this.state != PlaybackState.RUNNING) return;
        for (Context context : this.callbackContexts) {
            context.binding.callbacks().onLoop(context);
            if (this.state != PlaybackState.RUNNING) break;
        }
    }

    public boolean beginClose(PlaybackStopReason reason) {
        if (!isActive()) return false;
        this.state = PlaybackState.CLOSING;
        this.pendingSeek = null;
        this.pendingPlacement = null;
        if (this.preparedFrame != null) this.preparedFrame.cancelEvents();
        this.stopReason = Objects.requireNonNull(reason, "reason");
        return true;
    }

    public void closeCallbacks() {
        if (this.state != PlaybackState.CLOSING || this.callbacksClosed) return;
        this.callbacksClosed = true;
        onClose(this.stopReason);
        for (Context context : this.callbackContexts) {
            closeCallback(context);
        }
    }

    private void closeCallback(Context context) {
        if (!context.started) return;
        try {
            context.binding.callbacks().onClose(context);
        } catch (RuntimeException exception) {
            EmoteMod.LOGGER.warn("Emote close callback failed for {}", this.emoteId, exception);
        }
    }

    public void completeClose() {
        this.state = PlaybackState.CLOSED;
        for (Context context : this.callbackContexts) context.userState = null;
    }

    private final class Context implements PlaybackContext {
        private final CallbackRegistry.Binding binding;
        private final boolean animationScoped;
        private final long startTick;
        private int animationTick;
        private @Nullable PlaybackStopReason closeReason;
        private boolean started;
        private @Nullable Object userState;

        private Context(CallbackRegistry.Binding binding, boolean animationScoped) {
            this.binding = binding;
            this.animationScoped = animationScoped;
            this.startTick = PlaybackSession.this.elapsedTicks;
        }
        private EmotePlayback.PoseView pose() {
            EmotePlayback.PoseView notification = PlaybackSession.this.playback.notificationPose();
            return Objects.requireNonNull(notification == null ? PlaybackSession.this.appliedPose : notification);
        }
        public String getPayload() { return this.binding.payload(); }

        public UUID getSessionId() { return PlaybackSession.this.sessionId; }
        public MinecraftServer getServer() { return EmoteMod.SERVER; }
        public ServerLevel getWorld() { return Objects.requireNonNull(getServer().getLevel(PlaybackSession.this.levelKey), "Playback level unavailable."); }
        public long getElapsedTicks() { return PlaybackSession.this.elapsedTicks - this.startTick; }
        public int getTick() { return pose().tick(); }
        public @Nullable Integer getAnimationTick() { return this.animationScoped ? Integer.valueOf(this.animationTick) : pose().position().animationTick(); }
        public boolean setTick(int tick) { return PlaybackSession.this.setTick(tick); }
        public boolean setAnimationTick(int animationTick) { return PlaybackSession.this.queueAnimationTick(animationTick, pose()); }
        public boolean setStep(int stepIndex, int repeatIndex, int animationTick) { return PlaybackSession.this.setStep(stepIndex, repeatIndex, animationTick); }
        public Vec3 getRootPosition() { return pose().root().position(); }
        public Optional<PlaybackStopReason> getStopReason() { return Optional.ofNullable(this.closeReason != null ? this.closeReason : PlaybackSession.this.stopReason); }
        public @Nullable Object getUserState() { return this.userState; }
        public void setUserState(@Nullable Object state) { this.userState = state; }

        public Optional<Entity> getActor(String name) {
            Objects.requireNonNull(name, "name");
            return Optional.ofNullable(PlaybackSession.this.actors.get(name)).filter(entity -> !entity.isRemoved());
        }

        public Optional<Entity> getAttachmentEntity(String nodeId, String attachmentId) {
            Objects.requireNonNull(nodeId, "nodeId");
            return Optional.ofNullable(PlaybackSession.this.scene.nodes().get(nodeId))
                .map(node -> node.attachments().get(attachmentId))
                .flatMap(attachment -> Optional.<Entity>ofNullable(attachment.entity())).filter(entity -> !entity.isRemoved());
        }

        public Optional<Vec3> getNodeWorldPosition(String nodeId) {
            return PlaybackSession.this.nodeWorldPosition(nodeId, pose());
        }
    }

    public ResourceKey<Level> levelKey() {
        return this.levelKey;
    }

    public String emoteId() {
        return this.emoteId;
    }

    public PlaybackScene scene() {
        return this.scene;
    }

    public EmotePlayback playback() {
        return this.playback;
    }
}
