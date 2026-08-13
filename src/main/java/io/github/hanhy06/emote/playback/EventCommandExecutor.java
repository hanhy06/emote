package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.util.Objects;

public final class EventCommandExecutor implements EventPlayer.EventExecutor {
    private final ServerPlayer player;
    private final PlaybackNodes nodes;
    private final TimelinePlayer timeline;

    public EventCommandExecutor(
        ServerPlayer player,
        PlaybackNodes nodes,
        TimelinePlayer timeline
    ) {
        this.player = Objects.requireNonNull(player, "player");
        this.nodes = Objects.requireNonNull(nodes, "nodes");
        this.timeline = Objects.requireNonNull(timeline, "timeline");
    }

    @Override
    public void execute(EmoteAnimation.Event event) {
        CommandSourceStack source = createSource(event.source())
            .withPosition(resolveOrigin(event.origin()))
            .withLevel(this.player.level())
            .withPermission(LevelBasedPermissionSet.OWNER)
            .withSuppressedOutput();
        for (String command : event.commands()) {
            Emote.SERVER.getCommands().performPrefixedCommand(source, command);
        }
    }

    private CommandSourceStack createSource(EmoteAnimation.CommandSource source) {
        return switch (source.type()) {
            case PLAYER -> this.player.createCommandSourceStack();
            case SERVER -> Emote.SERVER.createCommandSourceStack().withLevel(this.player.level());
            case NODE -> {
                Entity entity = requiredEntity(source.node());
                yield Emote.SERVER.createCommandSourceStack()
                    .withLevel(this.player.level())
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
            root = this.nodes.root(node.node().space());
            displayMatrix = this.timeline.currentTransformation(origin.node()).getMatrix();
        }
        Matrix4fc matrix = root.worldMatrix(root.yaw(), displayMatrix);
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
