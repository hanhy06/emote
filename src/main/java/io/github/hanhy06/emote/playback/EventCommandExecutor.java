package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

public final class EventCommandExecutor implements EmotePlayback.EventExecutor {
    private final ServerLevel level;
    private final @Nullable CommandSourceStack actorSource;
    private final PlaybackScene scene;
    private final EmotePlayback playback;

    public EventCommandExecutor(
        ServerLevel level,
        @Nullable CommandSourceStack actorSource,
        PlaybackScene scene,
        EmotePlayback playback
    ) {
        this.level = Objects.requireNonNull(level, "level");
        this.actorSource = actorSource;
        this.scene = Objects.requireNonNull(scene, "scene");
        this.playback = Objects.requireNonNull(playback, "playback");
    }

    @Override
    public void execute(EmotePlayback.EventOccurrence preparedEvent) {
        EmoteAnimation.Event event = preparedEvent.event();
        if (event.externalKey() != null) throw new IllegalStateException("No resolver for external action " + event.externalKey());
        Vec3 origin = resolveOrigin(event.origin(), this.scene, this.playback);
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
                Entity entity = requiredEntity(source.node(), source.attachment());
                yield EmoteMod.SERVER.createCommandSourceStack()
                    .withLevel(this.level)
                    .withEntity(entity)
                    .withPosition(entity.position());
            }
        };
    }

    static Vec3 resolveOrigin(EmoteAnimation.CommandOrigin origin, PlaybackScene scene, EmotePlayback playback) {
        RootTransform root = scene.root();
        Matrix4fc displayMatrix;
        if (origin.type() == EmoteAnimation.OriginType.ROOT) {
            displayMatrix = root.rotationMatrix();
        } else {
            if (!scene.nodes().containsKey(origin.node())) throw new IllegalStateException("Command references missing playback node: " + origin.node());
            displayMatrix = playback.currentTransformation(origin.node()).getMatrix();
        }
        Matrix4fc matrix = root.worldMatrix(scene.orientationYaw(), displayMatrix);
        Vector3f position = matrix.transformPosition(new Vector3f(
            (float) origin.offset().x(),
            (float) origin.offset().y(),
            (float) origin.offset().z()
        ), new Vector3f());
        return root.position().add(position.x, position.y, position.z);
    }

    private Entity requiredEntity(String nodeId, String attachmentId) {
        PlaybackScene.NodeInstance node = requiredNode(nodeId);
        var attachment = node.attachments().get(attachmentId);
        if (attachment == null || attachment.entity() == null || attachment.entity().isRemoved()) {
            throw new IllegalStateException("Command source node entity is unavailable: " + nodeId);
        }
        return attachment.entity();
    }

    private PlaybackScene.NodeInstance requiredNode(String nodeId) {
        PlaybackScene.NodeInstance node = this.scene.nodes().get(nodeId);
        if (node == null) {
            throw new IllegalStateException("Command references missing playback node: " + nodeId);
        }
        return node;
    }
}
