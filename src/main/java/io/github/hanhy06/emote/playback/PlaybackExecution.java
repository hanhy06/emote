package io.github.hanhy06.emote.playback;

import com.google.gson.JsonObject;
import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.PlaybackHandle;
import io.github.hanhy06.emote.api.PlaybackContext;
import io.github.hanhy06.emote.api.TaskHandle;
import io.github.hanhy06.emote.api.PlaybackInfo;
import io.github.hanhy06.emote.api.PlaybackState;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.playback.session.PlaybackSession;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public final class PlaybackExecution implements PlaybackHandle {
    private final PlaybackSession session;
    private final PlaybackScheduler scheduler;
    private @Nullable Control control;
    private PlaybackState state = PlaybackState.RUNNING;
    private @Nullable PlaybackStopReason stopReason;
    private long elapsedTicks;
    private long lastServerTick = Long.MIN_VALUE;

    public PlaybackExecution(PlaybackSession session) {
        this.session = Objects.requireNonNull(session, "session");
        this.scheduler = new PlaybackScheduler(() -> requireControl().requireServerThread(),
            () -> this.state == PlaybackState.RUNNING || this.state == PlaybackState.FINISHING);
    }

    public void bind(Control control, long serverTick) {
        if (this.control != null) throw new IllegalStateException("Playback control is already bound.");
        this.control = Objects.requireNonNull(control, "control");
        this.lastServerTick = serverTick;
    }

    @Override
    public UUID sessionId() {
        return this.session.sessionId();
    }

    @Override
    public PlaybackInfo info() {
        return info(this.session.initiator().playerUuid());
    }

    public PlaybackInfo info(UUID playerUuid) {
        return new PlaybackInfo(sessionId(), playerUuid, Identifier.parse(this.session.id()), this.state,
            this.elapsedTicks, Identifier.parse(this.session.animationId()), this.session.animation().currentTick());
    }

    @Override
    public PlaybackState state() {
        return this.state;
    }

    @Override
    public Optional<PlaybackStopReason> stopReason() {
        return Optional.ofNullable(this.stopReason);
    }

    @Override
    public boolean pause() {
        requireControl().requireServerThread();
        if (this.state != PlaybackState.RUNNING) return false;
        this.state = PlaybackState.PAUSED;
        return true;
    }

    @Override
    public boolean resume() {
        requireControl().requireServerThread();
        if (this.state != PlaybackState.PAUSED) return false;
        this.state = PlaybackState.RUNNING;
        return true;
    }

    @Override
    public boolean finish() {
        Control control = requireControl();
        control.requireServerThread();
        if (isClosing() || this.state == PlaybackState.FINISHING) return false;
        this.state = PlaybackState.FINISHING;
        control.finish(this.session);
        return true;
    }

    @Override
    public boolean stop() {
        Control control = requireControl();
        control.requireServerThread();
        if (isClosing()) return false;
        control.stop(this.session);
        return true;
    }

    @Override
    public void onClose(Runnable cleanup) {
        requireControl().requireServerThread();
        if (isClosing()) throw new IllegalStateException("Playback is closing.");
        this.scheduler.root().onClose(cleanup);
    }

    public boolean tick(long serverTick) {
        if (isClosing() || this.state == PlaybackState.PAUSED || this.lastServerTick == serverTick) return false;
        this.lastServerTick = serverTick;
        this.elapsedTicks++;
        return true;
    }

    public boolean beginClose(PlaybackStopReason reason) {
        if (isClosing()) return false;
        this.state = PlaybackState.CLOSING;
        this.stopReason = Objects.requireNonNull(reason, "reason");
        this.scheduler.root().cancel();
        return true;
    }

    public void completeClose() {
        if (this.state != PlaybackState.CLOSING) throw new IllegalStateException("Playback is not closing.");
        this.state = PlaybackState.CLOSED;
    }

    private boolean isClosing() {
        return this.state == PlaybackState.CLOSING || this.state == PlaybackState.CLOSED;
    }

    private Control requireControl() {
        return Objects.requireNonNull(this.control, "Playback control has not been bound.");
    }

    public PlaybackScheduler scheduler() {
        return this.scheduler;
    }

    public PlaybackContext context(PlaybackScheduler.Scope scope, Vec3 origin) {
        return new Context(scope, Objects.requireNonNull(origin, "origin"));
    }

    private final class Context implements PlaybackContext {
        private final PlaybackScheduler.Scope scope;
        private final Vec3 origin;

        private Context(PlaybackScheduler.Scope scope, Vec3 origin) {
            this.scope = Objects.requireNonNull(scope, "scope");
            this.origin = origin;
        }

        public PlaybackHandle playback() { return PlaybackExecution.this; }

        public MinecraftServer server() {
            requireControl().requireServerThread();
            return EmoteMod.SERVER;
        }

        public ServerLevel level() {
            return Objects.requireNonNull(server().getLevel(PlaybackExecution.this.session.levelKey()), "Playback level is unavailable.");
        }

        public long elapsedTicks() { return this.scope.elapsedTicks(); }
        public int clipTick() { return PlaybackExecution.this.session.animation().currentTick(); }
        public Vec3 originAtInvocation() { return this.origin; }

        public Optional<Entity> actor(String name) {
            Objects.requireNonNull(name, "name");
            for (var participant : PlaybackExecution.this.session.participants()) {
                if (participant.role().name().toLowerCase(Locale.ROOT).equals(name)) {
                    return Optional.ofNullable(server().getPlayerList().getPlayer(participant.playerUuid()));
                }
            }
            return Optional.empty();
        }

        public Optional<Entity> nodeEntity(String nodeId) {
            server();
            var node = Objects.requireNonNull(PlaybackExecution.this.session.nodes().nodes().get(nodeId), "Unknown node " + nodeId);
            return Optional.<Entity>ofNullable(node.entity()).filter(entity -> !entity.isRemoved());
        }

        public Vec3 nodeWorldPosition(String nodeId) {
            server();
            var nodes = PlaybackExecution.this.session.nodes();
            var node = Objects.requireNonNull(nodes.nodes().get(nodeId), "Unknown node " + nodeId);
            var space = node.node().space();
            var root = nodes.root(space);
            var transform = PlaybackExecution.this.session.animation().currentTransformation(nodeId).getMatrix();
            Vector3f point = root.worldMatrix(nodes.orientationYaw(space), transform).transformPosition(new Vector3f());
            return root.position().add(point.x, point.y, point.z);
        }

        public TaskHandle afterTicks(long delay, Runnable action) { return this.scope.afterTicks(delay, action); }
        public TaskHandle everyTicks(long period, Runnable action) { return this.scope.everyTicks(period, action); }
        public TaskHandle onSignal(Identifier name, Consumer<JsonObject> handler) { return this.scope.onSignal(name, handler); }
        public void emitSignal(Identifier name, JsonObject parameters) { this.scope.emitSignal(name, parameters); }
        public void onClose(Runnable cleanup) { this.scope.onClose(cleanup); }
    }

    public interface Control {
        void requireServerThread();

        void finish(PlaybackSession session);

        void stop(PlaybackSession session);
    }
}
