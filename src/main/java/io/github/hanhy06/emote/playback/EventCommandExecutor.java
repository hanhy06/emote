package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.Vec2;
import net.minecraft.util.Mth;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

public final class EventCommandExecutor implements EmotePlayback.EventExecutor {
    private final ServerLevel level;
    private final @Nullable CommandSourceStack actorSource;
    private final PlaybackScene scene;

    public EventCommandExecutor(
        ServerLevel level,
        @Nullable CommandSourceStack actorSource,
        PlaybackScene scene
    ) {
        this.level = Objects.requireNonNull(level, "level");
        this.actorSource = actorSource;
        this.scene = Objects.requireNonNull(scene, "scene");
    }

    @Override
    public void execute(EmotePlayback.EventOccurrence preparedEvent) {
        EmoteAnimation.Event event = preparedEvent.event();
        if (event.externalKey() != null) throw new IllegalStateException("No resolver for external action " + event.externalKey());
        Vec3 origin = resolveOrigin(event.origin(), preparedEvent.pose());
        if (!event.commands().isEmpty()) {
            CommandSourceStack source = createSource(event.source())
                .withPosition(origin)
                .withLevel(this.level)
                .withPermission(LevelBasedPermissionSet.GAMEMASTER)
                .withSuppressedOutput();
            if (event.origin().useOriginRotation()) source = source.withRotation(resolveRotation(event.origin(), preparedEvent.pose()));
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

    static Vec3 resolveOrigin(EmoteAnimation.CommandOrigin origin, EmotePlayback.PoseView pose) {
        RootTransform root = pose.root();
        Matrix4fc displayMatrix;
        if (origin.type() == EmoteAnimation.OriginType.ROOT) {
            displayMatrix = root.rotationMatrix();
        } else {
            displayMatrix = pose.nodeMatrix(origin.node());
        }
        Matrix4fc matrix = root.worldMatrix(pose.orientationYaw(), displayMatrix);
        Vector3f position = matrix.transformPosition(new Vector3f(
            (float) origin.offset().x(),
            (float) origin.offset().y(),
            (float) origin.offset().z()
        ), new Vector3f());
        return root.position().add(position.x, position.y, position.z);
    }

    static Vec2 resolveRotation(EmoteAnimation.CommandOrigin origin, EmotePlayback.PoseView pose) {
        Matrix4fc displayMatrix = origin.type() == EmoteAnimation.OriginType.ROOT ? pose.root().rotationMatrix() : pose.nodeMatrix(origin.node());
        Vector3f angles = pose.root().worldMatrix(pose.orientationYaw(), displayMatrix)
            .getUnnormalizedRotation(new Quaternionf()).normalize().getEulerAnglesYXZ(new Vector3f());
        return new Vec2((float) -Math.toDegrees(angles.x), Mth.wrapDegrees(180.0F - (float) Math.toDegrees(angles.y)));
    }

    private Entity requiredEntity(String nodeId, String attachmentId) {
        PlaybackScene.NodeInstance node = this.scene.nodes().get(nodeId);
        if (node == null) {
            throw new IllegalStateException("Command references missing playback node: " + nodeId);
        }
        var attachment = node.attachments().get(attachmentId);
        if (attachment == null || attachment.entity() == null || attachment.entity().isRemoved()) {
            throw new IllegalStateException("Command source node entity is unavailable: " + nodeId);
        }
        return attachment.entity();
    }

}
