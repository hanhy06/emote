package io.github.hanhy06.emote.playback.session;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.PlaybackContext;
import io.github.hanhy06.emote.api.PlaybackInfo;
import io.github.hanhy06.emote.api.PlaybackPlacement;
import io.github.hanhy06.emote.api.PlaybackState;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.content.PreparedEmote;
import io.github.hanhy06.emote.playback.PlaybackPlayer;
import io.github.hanhy06.emote.playback.CallbackRegistry;
import io.github.hanhy06.emote.playback.runtime.PlaybackNodes;
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

public final class PlaybackSession implements PlaybackPlayer.LifecycleListener {
    private final UUID sessionId;
    private List<Context> callbackContexts = List.of();
    private List<Context> animationCallbackContexts = List.of();
    private Map<PreparedEmote, List<CallbackRegistry.Binding>> animationBindings = Map.of();
    private PlaybackState playbackState = PlaybackState.RUNNING;
    private @Nullable PlaybackStopReason stopReason;
    private long elapsedTicks;
    private long lastServerTick;
    private boolean callbacksStarted;
    private boolean invokingCallback;
    private boolean callbacksClosed;
    private boolean processingFrame;
    private boolean applyingTick;
    private @Nullable Integer pendingTick;
    private @Nullable Runnable deferredCleanup;
    private final ResourceKey<Level> levelKey;
    private final String emoteId;
    private final PlaybackNodes nodes;
    private final PlaybackPlayer playback;
    private final Map<String, Entity> actors;
    private PlaybackPlacement.Mode placementMode;

    public PlaybackSession(
        UUID sessionId,
        ResourceKey<Level> levelKey,
        String emoteId,
        PlaybackNodes nodes,
        PlaybackPlayer playback,
        Map<String, Entity> actors,
        PlaybackPlacement.Mode placementMode
    ) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.levelKey = Objects.requireNonNull(levelKey, "levelKey");
        this.emoteId = Objects.requireNonNull(emoteId, "emoteId");
        this.nodes = Objects.requireNonNull(nodes, "nodes");
        this.playback = Objects.requireNonNull(playback, "playback");
        this.actors = Map.copyOf(actors);
        this.placementMode = Objects.requireNonNull(placementMode, "placementMode");
    }

    public UUID sessionId() {
        return this.sessionId;
    }

    public PlaybackState playbackState() {
        return this.playbackState;
    }

    public PlaybackInfo playbackInfo() {
        UUID playerUuid = this.actors.get("actor") instanceof ServerPlayer player ? player.getUUID() : null;
        return new PlaybackInfo(this.sessionId, playerUuid, Identifier.parse(this.emoteId), this.playbackState,
            this.elapsedTicks, this.playback.currentTick(), this.playback.position(), placement());
    }

    public PlaybackPlacement placement() {
        return new PlaybackPlacement(this.placementMode, this.nodes.root().position(), this.nodes.orientationYaw());
    }

    public void setPlacementMode(PlaybackPlacement.Mode mode) {
        this.placementMode = Objects.requireNonNull(mode, "mode");
    }

    public Optional<Vec3> nodeWorldPosition(String nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        if (!this.nodes.nodes().containsKey(nodeId)) return Optional.empty();
        var transform = this.playback.currentTransformation(nodeId).getMatrix();
        Vector3f point = this.nodes.root().worldMatrix(this.nodes.orientationYaw(), transform).transformPosition(new Vector3f());
        return Optional.of(this.nodes.root().position().add(point.x, point.y, point.z));
    }

    public void bindCallbacks(
        List<CallbackRegistry.Binding> bindings,
        Map<PreparedEmote, List<CallbackRegistry.Binding>> animationBindings,
        long serverTick
    ) {
        this.callbackContexts = bindings.stream().map(binding -> new Context(binding, false)).toList();
        this.animationBindings = Map.copyOf(animationBindings);
        this.lastServerTick = serverTick;
        this.playback.bindLifecycleListener(this);
    }

    public void startPlayback() {
        beginFrame();
        try {
            if (this.callbacksStarted) throw new IllegalStateException("Callbacks already started.");
            this.playback.restoreDeferredVisibility();
            this.playback.startEvents();
            if (this.playbackState != PlaybackState.RUNNING) return;
            this.callbacksStarted = true;
            for (Context context : this.callbackContexts) {
                context.started = true;
                invokeCallback(context, context.binding.callbacks()::onStart);
                if (this.playbackState != PlaybackState.RUNNING) break;
            }
            startAnimationCallbacks();
        } finally {
            endFrame();
        }
    }

    public boolean setTick(int tick) {
        if (!this.playback.canSetTick(tick) || this.playbackState != PlaybackState.RUNNING) return false;
        this.pendingTick = tick;
        if (!this.processingFrame && !this.invokingCallback && !this.applyingTick) applyPendingTick();
        return true;
    }

    public boolean setAnimationTick(int tick) {
        if (tick < 0) throw new IllegalArgumentException("Tick must not be negative");
        if (this.playbackState != PlaybackState.RUNNING) return false;
        int target = this.playback.animationTickTarget(tick);
        return target >= 0 && setTick(target);
    }

    public boolean setStep(int stepIndex, int repeatIndex, int tick) {
        if (stepIndex < 0 || repeatIndex < 0 || tick < 0) throw new IllegalArgumentException("Step, repeat and tick must not be negative");
        if (this.playbackState != PlaybackState.RUNNING) return false;
        return setTick(this.playback.stepTickTarget(stepIndex, repeatIndex, tick));
    }

    public void beginFrame() {
        this.processingFrame = true;
    }

    public void endFrame() {
        this.processingFrame = false;
        applyPendingTick();
    }

    private void applyPendingTick() {
        Integer tick = this.pendingTick;
        this.pendingTick = null;
        if (tick == null || this.playbackState != PlaybackState.RUNNING) return;
        this.applyingTick = true;
        try {
            this.playback.setTick(tick);
        } finally {
            this.applyingTick = false;
        }
    }

    @Override
    public boolean hasPendingTick() {
        return this.pendingTick != null;
    }

    @Override
    public void onPositionChanged(int animationTick) {
        for (Context context : this.animationCallbackContexts) context.localTick = animationTick;
    }

    private void startAnimationCallbacks() {
        for (Context context : this.animationCallbackContexts) {
            if (this.playbackState != PlaybackState.RUNNING) break;
            context.started = true;
            invokeCallback(context, context.binding.callbacks()::onStart);
        }
    }

    @Override
    public void onStart(PreparedEmote animation) {
        this.animationCallbackContexts = this.animationBindings.getOrDefault(animation, List.of()).stream()
            .map(binding -> new Context(binding, true)).toList();
        Integer tick = this.playback.position().animationTick();
        onPositionChanged(tick == null ? 0 : tick);
        if (this.callbacksStarted) startAnimationCallbacks();
    }

    @Override
    public void onTick(int animationTick) {
        onPositionChanged(animationTick);
        for (Context context : this.animationCallbackContexts) {
            if (this.playbackState != PlaybackState.RUNNING) break;
            invokeCallback(context, context.binding.callbacks()::onTick);
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

    public boolean tick(long serverTick) {
        if (this.playbackState != PlaybackState.RUNNING || this.lastServerTick == serverTick) return false;
        this.lastServerTick = serverTick;
        this.elapsedTicks++;
        return true;
    }

    public void tickCallbacks() {
        if (!this.callbacksStarted || this.playbackState != PlaybackState.RUNNING) return;
        for (Context context : this.callbackContexts) {
            invokeCallback(context, context.binding.callbacks()::onTick);
            if (this.playbackState != PlaybackState.RUNNING) break;
        }
    }

    private void loopCallbacks() {
        if (!this.callbacksStarted || this.playbackState != PlaybackState.RUNNING) return;
        for (Context context : this.callbackContexts) {
            invokeCallback(context, context.binding.callbacks()::onLoop);
            if (this.playbackState != PlaybackState.RUNNING) break;
        }
    }

    private void invokeCallback(Context context, Consumer<PlaybackContext> callback) {
        this.invokingCallback = true;
        try {
            callback.accept(context);
        } finally {
            this.invokingCallback = false;
            Runnable cleanup = this.deferredCleanup;
            this.deferredCleanup = null;
            if (cleanup != null) cleanup.run();
        }
    }

    public boolean deferCleanup(Runnable cleanup) {
        if (!this.invokingCallback) return false;
        if (this.deferredCleanup == null) this.deferredCleanup = cleanup;
        return true;
    }

    public boolean isInvokingCallback() {
        return this.invokingCallback;
    }

    public boolean beginClose(PlaybackStopReason reason) {
        if (this.playbackState != PlaybackState.RUNNING) return false;
        this.playbackState = PlaybackState.CLOSING;
        this.pendingTick = null;
        this.stopReason = Objects.requireNonNull(reason, "reason");
        return true;
    }

    public void closeCallbacks() {
        if (this.playbackState != PlaybackState.CLOSING || this.callbacksClosed) return;
        this.callbacksClosed = true;
        onClose(this.stopReason);
        for (Context context : this.callbackContexts) {
            closeCallback(context);
        }
    }

    private void closeCallback(Context context) {
        if (!context.started) return;
        try {
            invokeCallback(context, context.binding.callbacks()::onClose);
        } catch (RuntimeException exception) {
            EmoteMod.LOGGER.warn("Emote close callback failed for {}", this.emoteId, exception);
        }
    }

    public void completeClose() {
        this.playbackState = PlaybackState.CLOSED;
        for (Context context : this.callbackContexts) context.userState = null;
    }

    private final class Context implements PlaybackContext {
        private final CallbackRegistry.Binding binding;
        private final boolean animationScoped;
        private final long startTick;
        private int localTick;
        private @Nullable PlaybackStopReason closeReason;
        private boolean started;
        private @Nullable Object userState;

        private Context(CallbackRegistry.Binding binding, boolean animationScoped) {
            this.binding = binding;
            this.animationScoped = animationScoped;
            this.startTick = PlaybackSession.this.elapsedTicks;
        }
        public String getPayload() { return this.binding.payload(); }

        public UUID getSessionId() { return PlaybackSession.this.sessionId; }
        public MinecraftServer getServer() { return EmoteMod.SERVER; }
        public ServerLevel getWorld() { return Objects.requireNonNull(getServer().getLevel(PlaybackSession.this.levelKey), "Playback level unavailable."); }
        public long getElapsedTicks() { return PlaybackSession.this.elapsedTicks - this.startTick; }
        public int getTick() { return PlaybackSession.this.playback.currentTick(); }
        public @Nullable Integer getAnimationTick() { return this.animationScoped ? Integer.valueOf(this.localTick) : PlaybackSession.this.playback.position().animationTick(); }
        public boolean setTick(int tick) { return PlaybackSession.this.setTick(tick); }
        public boolean setAnimationTick(int tick) { return PlaybackSession.this.setAnimationTick(tick); }
        public boolean setStep(int stepIndex, int repeatIndex, int tick) { return PlaybackSession.this.setStep(stepIndex, repeatIndex, tick); }
        public Vec3 getRootPosition() { return PlaybackSession.this.nodes.root().position(); }
        public Optional<PlaybackStopReason> getStopReason() { return Optional.ofNullable(this.closeReason != null ? this.closeReason : PlaybackSession.this.stopReason); }
        public @Nullable Object getUserState() { return this.userState; }
        public void setUserState(@Nullable Object state) { this.userState = state; }

        public Optional<Entity> getActor(String name) {
            Objects.requireNonNull(name, "name");
            return Optional.ofNullable(PlaybackSession.this.actors.get(name)).filter(entity -> !entity.isRemoved());
        }

        public Optional<Entity> getNodeEntity(String nodeId) {
            Objects.requireNonNull(nodeId, "nodeId");
            return Optional.ofNullable(PlaybackSession.this.nodes.nodes().get(nodeId))
                .flatMap(node -> Optional.<Entity>ofNullable(node.entity())).filter(entity -> !entity.isRemoved());
        }

        public Optional<Vec3> getNodeWorldPosition(String nodeId) {
            return PlaybackSession.this.nodeWorldPosition(nodeId);
        }
    }

    public ResourceKey<Level> levelKey() {
        return this.levelKey;
    }

    public String emoteId() {
        return this.emoteId;
    }

    public PlaybackNodes nodes() {
        return this.nodes;
    }

    public PlaybackPlayer playback() {
        return this.playback;
    }
}
