package io.github.hanhy06.emote.playback.session;

import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.ParticipantRole;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.content.PreparedSequence;
import io.github.hanhy06.emote.playback.AnimationPlayer;
import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.playback.CallbackRegistry;
import io.github.hanhy06.emote.api.PlaybackContext;
import io.github.hanhy06.emote.api.PlaybackInfo;
import io.github.hanhy06.emote.api.PlaybackState;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import java.util.function.Consumer;
import io.github.hanhy06.emote.playback.runtime.PlaybackNodes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.*;

public final class PlaybackSession {
    private final UUID sessionId;
    private List<Context> callbackContexts = List.of();
    private PlaybackState playbackState = PlaybackState.RUNNING;
    private @Nullable PlaybackStopReason stopReason;
    private long elapsedTicks;
    private long lastServerTick;
    private boolean callbacksStarted;
    private boolean invokingCallback;
    private @Nullable Runnable deferredCleanup;
    private final ResourceKey<Level> levelKey;
    private final String id;
    private final String animationId;
    private final PlaybackNodes nodes;
    private AnimationPlayer animation;
    private final EmotePlayerBehavior playerBehavior;
    private final @Nullable PreparedSequence partnerSequence;
    private final EnumMap<ParticipantRole, PlaybackParticipant> participants = new EnumMap<>(ParticipantRole.class);
    private final Collection<PlaybackParticipant> participantView = Collections.unmodifiableCollection(this.participants.values());
    private final Map<ParticipantRole, PlaybackParticipant> participantMapView = Collections.unmodifiableMap(this.participants);

    private State state;
    private int remainingTimeoutTicks;
    private @Nullable PlaybackParticipant reservedPartner;
    private @Nullable PlaybackStopReason pendingStopReason;

    public PlaybackSession(
        UUID sessionId,
        ResourceKey<Level> levelKey,
        String id,
        String animationId,
        PlaybackNodes nodes,
        AnimationPlayer animation,
        EmotePlayerBehavior playerBehavior,
        PlaybackParticipant initiator,
        @Nullable PreparedSequence partnerSequence
    ) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.levelKey = Objects.requireNonNull(levelKey, "levelKey");
        this.id = Objects.requireNonNull(id, "id");
        this.animationId = Objects.requireNonNull(animationId, "animationId");
        this.nodes = Objects.requireNonNull(nodes, "nodes");
        this.animation = Objects.requireNonNull(animation, "animation");
        this.playerBehavior = Objects.requireNonNull(playerBehavior, "playerBehavior");
        this.partnerSequence = partnerSequence;
        this.state = partnerSequence == null ? State.SOLO : State.OFFERING;
        this.remainingTimeoutTicks = partnerSequence == null ? 0 : partnerSequence.partnerPlayback().timeoutTicks();
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

    public PlaybackInfo playbackInfo(UUID playerUuid) {
        return new PlaybackInfo(this.sessionId, playerUuid, Identifier.parse(this.id), this.playbackState,
            this.elapsedTicks, this.animation.emoteId(), this.animation.currentTick());
    }

    public void bindCallbacks(List<CallbackRegistry.Binding> bindings, long serverTick) {
        this.callbackContexts = bindings.stream().map(Context::new).toList();
        this.lastServerTick = serverTick;
        this.animation.bindLoopListener(this::loopCallbacks);
    }

    public void startCallbacks() {
        if (this.callbacksStarted) throw new IllegalStateException("Callbacks already started.");
        this.callbacksStarted = true;
        for (Context context : this.callbackContexts) {
            context.started = true;
            invokeCallback(context, context.binding.callbacks()::onStart);
            if (this.playbackState != PlaybackState.RUNNING) break;
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

    public boolean beginClose(PlaybackStopReason reason) {
        if (this.playbackState != PlaybackState.RUNNING) return false;
        this.playbackState = PlaybackState.CLOSING;
        this.stopReason = Objects.requireNonNull(reason, "reason");
        for (Context context : this.callbackContexts) {
            if (!context.started) continue;
            try {
                context.binding.callbacks().onClose(context);
            } catch (RuntimeException exception) {
                EmoteMod.LOGGER.warn("Emote close callback failed for {}", this.id, exception);
            }
        }
        return true;
    }

    public void completeClose() {
        this.playbackState = PlaybackState.CLOSED;
        for (Context context : this.callbackContexts) context.userState = null;
    }

    private final class Context implements PlaybackContext {
        private final CallbackRegistry.Binding binding;
        private boolean started;
        private @Nullable Object userState;

        private Context(CallbackRegistry.Binding binding) { this.binding = binding; }
        public String payload() { return this.binding.payload(); }

        public UUID sessionId() { return PlaybackSession.this.sessionId; }
        public MinecraftServer server() { return EmoteMod.SERVER; }
        public ServerLevel level() { return Objects.requireNonNull(server().getLevel(PlaybackSession.this.levelKey), "Playback level unavailable."); }
        public long elapsedTicks() { return PlaybackSession.this.elapsedTicks; }
        public int animationTick() { return PlaybackSession.this.animation.currentTick(); }
        public Vec3 rootPosition() { return PlaybackSession.this.nodes.root().position(); }
        public Optional<PlaybackStopReason> stopReason() { return Optional.ofNullable(PlaybackSession.this.stopReason); }
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

    public boolean hasPartner() {
        return this.partnerSequence != null;
    }

    public PreparedSequence partnerSequence() {
        if (this.partnerSequence == null) {
            throw new IllegalStateException("Solo sessions do not have a partner sequence");
        }
        return this.partnerSequence;
    }

    public State state() {
        return this.state;
    }

    public void enterWaiting() {
        if (this.state != State.OFFERING || this.reservedPartner != null) {
            throw new IllegalStateException("Only an unreserved offer can start waiting for a partner");
        }
        this.state = State.WAITING;
    }

    public boolean tickTimeout() {
        if (this.state != State.WAITING) {
            throw new IllegalStateException("Session is not waiting for a partner");
        }
        return --this.remainingTimeoutTicks <= 0;
    }

    public void reservePartner(PlaybackParticipant participant) {
        if (!acceptsPartner()) {
            throw new IllegalStateException("Session is not accepting a partner");
        }
        if (participant.role() != ParticipantRole.PARTNER) {
            throw new IllegalArgumentException("Reserved participant must use the partner role");
        }
        this.reservedPartner = participant;
    }

    public boolean acceptsPartner() {
        return this.pendingStopReason == null
            && (this.state == State.OFFERING || this.state == State.WAITING)
            && this.reservedPartner == null;
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

    public @Nullable PlaybackParticipant reservedPartner() {
        return this.reservedPartner;
    }

    public PlaybackParticipant activateReservedPartner(AnimationPlayer animation) {
        if (this.state != State.OFFERING && this.state != State.WAITING) {
            throw new IllegalStateException("Session cannot activate a partner in state " + this.state);
        }
        PlaybackParticipant participant = Objects.requireNonNull(this.reservedPartner, "reservedPartner");
        this.reservedPartner = null;
        addParticipant(participant);
        replaceAnimation(animation, State.MATCHED);
        return participant;
    }

    public @Nullable PlaybackParticipant releaseReservedPartner() {
        PlaybackParticipant participant = this.reservedPartner;
        this.reservedPartner = null;
        return participant;
    }

    public void beginTimeout(AnimationPlayer animation) {
        if (this.state != State.WAITING || this.reservedPartner != null) {
            throw new IllegalStateException("Only an unreserved waiting session can time out");
        }
        replaceAnimation(animation, State.TIMEOUT);
    }

    private void replaceAnimation(AnimationPlayer animation, State state) {
        this.animation = Objects.requireNonNull(animation, "animation");
        this.animation.bindLoopListener(this::loopCallbacks);
        this.state = Objects.requireNonNull(state, "state");
    }

    public Collection<PlaybackParticipant> participants() {
        return this.participantView;
    }

    public Map<ParticipantRole, PlaybackParticipant> participantsByRole() {
        return this.participantMapView;
    }

    public enum State {
        SOLO,
        OFFERING,
        WAITING,
        MATCHED,
        TIMEOUT
    }
}
