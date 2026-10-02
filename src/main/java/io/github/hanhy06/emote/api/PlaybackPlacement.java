package io.github.hanhy06.emote.api;

import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

import java.util.Objects;

/** World-space scene placement in the actor's dimension, using Minecraft yaw in [-180, 180).
 * PLAYER inputs use the actor's normal placement rules and ignore position/yaw;
 * playback snapshots always contain the actual position and orientation.
 */
public record PlaybackPlacement(Mode mode, Vec3 position, float yaw) {
    public enum Mode { PLAYER, EXTERNAL }

    public PlaybackPlacement {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(position, "position");
        if (!Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z) || !Float.isFinite(yaw)) {
            throw new IllegalArgumentException("Placement position and yaw must be finite");
        }
        yaw = Mth.wrapDegrees(yaw);
    }

    public static PlaybackPlacement player() {
        return new PlaybackPlacement(Mode.PLAYER, Vec3.ZERO, 0);
    }

    public static PlaybackPlacement external(Vec3 position, float yaw) {
        return new PlaybackPlacement(Mode.EXTERNAL, position, yaw);
    }
}
