package io.github.hanhy06.emote.playback.runtime;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.content.EmoteSequence;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.EnumMap;
import java.util.Map;

public final class SceneRootResolver {
    public static Map<EmoteAnimation.NodeSpace, RootTransform> single(RootTransform root) {
        EnumMap<EmoteAnimation.NodeSpace, RootTransform> roots = new EnumMap<>(EmoteAnimation.NodeSpace.class);
        for (EmoteAnimation.NodeSpace space : EmoteAnimation.NodeSpace.values()) {
            roots.put(space, root);
        }
        return Map.copyOf(roots);
    }

    public Map<EmoteAnimation.NodeSpace, RootTransform> resolve(
        ServerPlayer initiator,
        EmoteSequence.Participants participants
    ) {
        RootTransform scene = RootTransform.fromPlayer(initiator);
        CommandSourceStack source = initiator.createCommandSourceStack()
            .withPosition(scene.position())
            .withRotation(new Vec2(0.0F, scene.yaw()))
            .withAnchor(EntityAnchorArgument.Anchor.FEET);

        EnumMap<EmoteAnimation.NodeSpace, RootTransform> roots = new EnumMap<>(EmoteAnimation.NodeSpace.class);
        roots.put(EmoteAnimation.NodeSpace.SCENE, scene);
        roots.put(EmoteAnimation.NodeSpace.INITIATOR, resolve(participants.initiator(), source));
        roots.put(EmoteAnimation.NodeSpace.PARTNER, resolve(participants.partner(), source));
        return Map.copyOf(roots);
    }

    private RootTransform resolve(EmoteSequence.ParticipantPlacement placement, CommandSourceStack source) {
        Vec3 position = placement.position().getPosition(source);
        Vec2 rotation = placement.rotation().getRotation(source);
        return RootTransform.create(position, rotation.y);
    }
}
