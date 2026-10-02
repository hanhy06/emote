package io.github.hanhy06.emote.playback.timeline;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.content.PreparedAnimation;
import io.github.hanhy06.emote.playback.AnimationPlayer;
import io.github.hanhy06.emote.playback.runtime.PlaybackNodes;
import io.github.hanhy06.emote.playback.runtime.RootTransform;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

public final class EventCommandExecutor implements AnimationPlayer.EventExecutor {
    private final ServerLevel level;
    private final @Nullable CommandSourceStack actorSource;
    private final PlaybackNodes nodes;
    private final AnimationPlayer timeline;

    public EventCommandExecutor(
        ServerLevel level,
        @Nullable CommandSourceStack actorSource,
        PlaybackNodes nodes,
        AnimationPlayer timeline
    ) {
        this.level = Objects.requireNonNull(level, "level");
        this.actorSource = actorSource;
        this.nodes = Objects.requireNonNull(nodes, "nodes");
        this.timeline = Objects.requireNonNull(timeline, "timeline");
    }

    @Override
    public void execute(PreparedAnimation.PreparedEvent preparedEvent) {
        EmoteAnimation.Event event = preparedEvent.event();
        Vec3 origin = resolveOrigin(event.origin());
        if (!event.commands().isEmpty()) {
            CommandSourceStack source = createSource(event.source())
                .withPosition(origin)
                .withLevel(this.level)
                .withPermission(LevelBasedPermissionSet.GAMEMASTER)
                .withSuppressedOutput();
            for (String command : event.commands()) {
                EmoteMod.SERVER.getCommands().performPrefixedCommand(source, command);
            }
        }
    }

    private CommandSourceStack createSource(EmoteAnimation.CommandSource source) {
        return switch (source.type()) {
            case PLAYER -> {
                if (this.actorSource == null) throw new IllegalStateException("Playback has no actor command source.");
                Entity actor = this.actorSource.getEntity();
                yield actor == null ? this.actorSource : this.actorSource.withRotation(actor.getRotationVector());
            }
            case SERVER -> EmoteMod.SERVER.createCommandSourceStack().withLevel(this.level);
            case NODE -> {
                Entity entity = requiredEntity(source.node());
                yield EmoteMod.SERVER.createCommandSourceStack()
                    .withLevel(this.level)
                    .withEntity(entity)
                    .withPosition(entity.position());
            }
        };
    }

    private Vec3 resolveOrigin(EmoteAnimation.CommandOrigin origin) {
        RootTransform root = this.nodes.root();
        Matrix4fc displayMatrix;
        if (origin.type() == EmoteAnimation.OriginType.ROOT) {
            displayMatrix = root.rotationMatrix();
        } else {
            PlaybackNodes.NodeInstance node = requiredNode(origin.node());
            displayMatrix = this.timeline.currentTransformation(origin.node()).getMatrix();
        }
        Matrix4fc matrix = root.worldMatrix(this.nodes.orientationYaw(), displayMatrix);
        Vector3f position = matrix.transformPosition(new Vector3f(
            (float) origin.offset().x(),
            (float) origin.offset().y(),
            (float) origin.offset().z()
        ), new Vector3f());
        return root.position().add(position.x, position.y, position.z);
    }

    private Entity requiredEntity(String nodeId) {
        PlaybackNodes.NodeInstance node = requiredNode(nodeId);
        if (node.entity() == null || node.entity().isRemoved()) {
            throw new IllegalStateException("Command source node entity is unavailable: " + nodeId);
        }
        return node.entity();
    }

    private PlaybackNodes.NodeInstance requiredNode(String nodeId) {
        PlaybackNodes.NodeInstance node = this.nodes.nodes().get(nodeId);
        if (node == null) {
            throw new IllegalStateException("Command references missing playback node: " + nodeId);
        }
        return node;
    }
}
