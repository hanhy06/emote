package io.github.hanhy06.emote.playback.session;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.*;
import io.github.hanhy06.emote.content.PreparedAnimation;
import io.github.hanhy06.emote.playback.AnimationPlayer;
import io.github.hanhy06.emote.playback.CallbackRegistry;
import io.github.hanhy06.emote.playback.runtime.PlaybackNodes;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;

public final class PlaybackSession implements AnimationPlayer.LifecycleListener {
    private final UUID sessionId;
    private List<Context> callbackContexts = List.of();
    private List<Context> animationCallbackContexts = List.of();
    private Map<PreparedAnimation, List<CallbackRegistry.Binding>> animationBindings = Map.of();
    private PlaybackState playbackState = PlaybackState.RUNNING;
    private @Nullable PlaybackStopReason stopReason;
    private long elapsedTicks;
    private long lastServerTick;
    private boolean callbacksStarted;
    private boolean invokingCallback;
    private boolean callbacksClosed;
    private @Nullable Runnable deferredCleanup;
    private final ResourceKey<Level> levelKey;
    private final String id;
    private final String animationId;
    private final PlaybackNodes nodes;
    private final AnimationPlayer animation;
    private final EmotePlayerBehavior playerBehavior;
    private final EnumMap<ParticipantRole, PlaybackParticipant> participants = new EnumMap<>(ParticipantRole.class);
    private final Collection<PlaybackParticipant> participantView = Collections.unmodifiableCollection(this.participants.values());
    private final Map<ParticipantRole, PlaybackParticipant> participantMapView = Collections.unmodifiableMap(this.participants);

    private @Nullable PlaybackStopReason pendingStopReason;

    public PlaybackSession(
        UUID sessionId,
        ResourceKey<Level> levelKey,
        String id,
        String animationId,
        PlaybackNodes nodes,
        AnimationPlayer animation,
        EmotePlayerBehavior playerBehavior,
        PlaybackParticipant initiator
    ) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.levelKey = Objects.requireNonNull(levelKey, "levelKey");
        this.id = Objects.requireNonNull(id, "id");
        this.animationId = Objects.requireNonNull(animationId, "animationId");
        this.nodes = Objects.requireNonNull(nodes, "nodes");
        this.animation = Objects.requireNonNull(animation, "animation");
        this.playerBehavior = Objects.requireNonNull(playerBehavior, "playerBehavior");
        addParticipant(Objects.requireNonNull(initiator, "initiator"));
        if (initiator.role() != ParticipantRole.INITIATOR) {
            throw new IllegalArgumentException("A playback session must start with an initiator");
        }
    }

    void addParticipant(PlaybackParticipant participant) {
        PlaybackParticipant previous = this.participants.putIfAbsent(participant.role(), participant);
        if (previous != null) {
            throw new IllegalStateException("Participant role is already occupied: " + participant.role());
        }
    }

    public UUID sessionId() {
        return this.sessionId;
    }

    public PlaybackState playbackState() {
        return this.playbackState;
    }

    public PlaybackInfo playbackInfo(UUID playerUuid) {
        return new PlaybackInfo(this.sessionId, playerUuid, Identifier.parse(this.id), this.playbackState,
            this.elapsedTicks, this.animation.emoteId(), this.animation.currentTick());
    }

    public void bindCallbacks(
        List<CallbackRegistry.Binding> bindings,
        Map<PreparedAnimation, List<CallbackRegistry.Binding>> animationBindings,
        long serverTick
    ) {
        this.callbackContexts = bindings.stream().map(binding -> new Context(binding, false)).toList();
        this.animationBindings = Map.copyOf(animationBindings);
        this.lastServerTick = serverTick;
        this.animation.bindLifecycleListener(this);
    }

    public void startPlayback() {
        if (this.callbacksStarted) throw new IllegalStateException("Callbacks already started.");
        this.animation.restoreDeferredVisibility();
        this.animation.startEvents();
        if (this.playbackState != PlaybackState.RUNNING) return;
        this.callbacksStarted = true;
        for (Context context : this.callbackContexts) {
            context.started = true;
            invokeCallback(context, context.binding.callbacks()::onStart);
            if (this.playbackState != PlaybackState.RUNNING) break;
        }
        startAnimationCallbacks();
    }

    private void startAnimationCallbacks() {
        for (Context context : this.animationCallbackContexts) {
            if (this.playbackState != PlaybackState.RUNNING) break;
            context.started = true;
            invokeCallback(context, context.binding.callbacks()::onStart);
        }
    }

    @Override
    public void onStart(PreparedAnimation animation) {
        this.animationCallbackContexts = this.animationBindings.getOrDefault(animation, List.of()).stream()
            .map(binding -> new Context(binding, true)).toList();
        if (this.callbacksStarted) startAnimationCallbacks();
    }

    @Override
    public void onTick(int animationTick) {
        for (Context context : this.animationCallbackContexts) context.localTick = animationTick;
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
            EmoteMod.LOGGER.warn("Emote close callback failed for {}", this.id, exception);
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
        public String payload() { return this.binding.payload(); }

        public UUID sessionId() { return PlaybackSession.this.sessionId; }
        public MinecraftServer server() { return EmoteMod.SERVER; }
        public ServerLevel level() { return Objects.requireNonNull(server().getLevel(PlaybackSession.this.levelKey), "Playback level unavailable."); }
        public long elapsedTicks() { return PlaybackSession.this.elapsedTicks - this.startTick; }
        public int animationTick() { return this.animationScoped ? this.localTick : PlaybackSession.this.animation.currentTick(); }
        public Vec3 rootPosition() { return PlaybackSession.this.nodes.root().position(); }
        public Optional<PlaybackStopReason> stopReason() { return Optional.ofNullable(this.closeReason != null ? this.closeReason : PlaybackSession.this.stopReason); }
        public @Nullable Object userState() { return this.userState; }
        public void setUserState(@Nullable Object state) { this.userState = state; }

        public Optional<Entity> actor(String name) {
            Objects.requireNonNull(name, "name");
            for (PlaybackParticipant participant : PlaybackSession.this.participants()) {
                if (participant.role().name().equalsIgnoreCase(name)) {
                    return Optional.ofNullable(server().getPlayerList().getPlayer(participant.playerUuid()));
                }
            }
            return Optional.empty();
        }

        public Optional<Entity> nodeEntity(String nodeId) {
            var node = Objects.requireNonNull(PlaybackSession.this.nodes.nodes().get(nodeId), "Unknown node " + nodeId);
            return Optional.<Entity>ofNullable(node.entity()).filter(entity -> !entity.isRemoved());
        }

        public Vec3 nodeWorldPosition(String nodeId) {
            var nodes = PlaybackSession.this.nodes;
            var node = Objects.requireNonNull(nodes.nodes().get(nodeId), "Unknown node " + nodeId);
            var space = node.node().space();
            var root = nodes.root(space);
            var transform = PlaybackSession.this.animation.currentTransformation(nodeId).getMatrix();
            Vector3f point = root.worldMatrix(nodes.orientationYaw(space), transform).transformPosition(new Vector3f());
            return root.position().add(point.x, point.y, point.z);
        }
    }

    public ResourceKey<Level> levelKey() {
        return this.levelKey;
    }

    public String id() {
        return this.id;
    }

    public String animationId() {
        return this.animationId;
    }

    public PlaybackNodes nodes() {
        return this.nodes;
    }

    public AnimationPlayer animation() {
        return this.animation;
    }

    public EmotePlayerBehavior playerBehavior() {
        return this.playerBehavior;
    }

    public PlaybackParticipant initiator() {
        return this.participants.get(ParticipantRole.INITIATOR);
    }

    public PlaybackParticipant participant(UUID playerUuid) {
        for (PlaybackParticipant participant : this.participants.values()) {
            if (participant.playerUuid().equals(playerUuid)) {
                return participant;
            }
        }
        return null;
    }

    public boolean requestStop(PlaybackStopReason reason) {
        if (this.pendingStopReason != null) {
            return true;
        }
        if (this.animation.requestOutro() == AnimationPlayer.OutroRequestResult.UNSUPPORTED) {
            return false;
        }
        this.pendingStopReason = Objects.requireNonNull(reason, "reason");
        return true;
    }

    public @Nullable PlaybackStopReason pendingStopReason() {
        return this.pendingStopReason;
    }

    public Collection<PlaybackParticipant> participants() {
        return this.participantView;
    }

    public Map<ParticipantRole, PlaybackParticipant> participantsByRole() {
        return this.participantMapView;
    }

}
