package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.api.ParticipantRole;
import io.github.hanhy06.emote.skin.animation.AnimationSkinBinding;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record PlaybackParticipant(
    UUID playerUuid,
    ParticipantRole role,
    Vec3 startPosition,
    List<AnimationSkinBinding> skinParts,
    boolean wasInvisible
) {
    public PlaybackParticipant {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(startPosition, "startPosition");
        skinParts = List.copyOf(skinParts);
    }
}
