package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.animation.EmoteAnimation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.util.Objects;

public final class JsonEventCommandExecutor implements JsonEventPlayer.EventExecutor {
    private final MinecraftServer server;
    private final ServerPlayer player;
    private final JsonPlaybackNodes nodes;
    private final JsonTimelinePlayer timeline;

    public JsonEventCommandExecutor(
        MinecraftServer server,
        ServerPlayer player,
        JsonPlaybackNodes nodes,
        JsonTimelinePlayer timeline
    ) {
        this.server = Objects.requireNonNull(server, "server");
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
            this.server.getCommands().performPrefixedCommand(source, command);
        }
    }

    private CommandSourceStack createSource(EmoteAnimation.CommandSource source) {
        return switch (source.type()) {
            case PLAYER -> this.player.createCommandSourceStack();
            case SERVER -> this.server.createCommandSourceStack().withLevel(this.player.level());
            case NODE -> {
                Entity entity = requiredEntity(source.node());
                yield this.server.createCommandSourceStack()
                    .withLevel(this.player.level())
                    .withEntity(entity)
                    .withPosition(entity.position());
            }
        };
    }

    private Vec3 resolveOrigin(EmoteAnimation.CommandOrigin origin) {
        Matrix4fc matrix;
        if (origin.type() == EmoteAnimation.OriginType.ROOT) {
            matrix = this.nodes.root().rotationMatrix();
        } else {
            requiredNode(origin.node());
            matrix = this.timeline.currentTransformation(origin.node()).getMatrix();
        }
        Vector3f position = matrix.transformPosition(new Vector3f(
            (float)origin.offset().x(),
            (float)origin.offset().y(),
            (float)origin.offset().z()
        ), new Vector3f());
        return this.nodes.root().position().add(position.x, position.y, position.z);
    }

    private Entity requiredEntity(String nodeId) {
        JsonPlaybackNodes.NodeInstance node = requiredNode(nodeId);
        if (node.entity() == null || node.entity().isRemoved()) {
            throw new IllegalStateException("Command source node entity is unavailable: " + nodeId);
        }
        return node.entity();
    }

    private JsonPlaybackNodes.NodeInstance requiredNode(String nodeId) {
        JsonPlaybackNodes.NodeInstance node = this.nodes.nodes().get(nodeId);
        if (node == null) {
            throw new IllegalStateException("Command references missing playback node: " + nodeId);
        }
        return node;
    }
}
