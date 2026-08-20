package io.github.hanhy06.emote.playback.runtime;

import com.mojang.math.Transformation;
import io.github.hanhy06.emote.content.PreparedEmote;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public record RootTransform(Vec3 position, float yaw, Matrix4f rotationMatrix, Quaternionf rotation) {
    private static final float MODEL_FORWARD_YAW_OFFSET = 180.0F;

    public RootTransform {
        rotationMatrix = new Matrix4f(rotationMatrix);
        rotation = new Quaternionf(rotation);
    }

    public static RootTransform fromPlayer(ServerPlayer player) {
        return create(player.position(), player.getYRot());
    }

    public static RootTransform create(Vec3 position, float yaw) {
        float rotationRadians = (float) Math.toRadians(MODEL_FORWARD_YAW_OFFSET - yaw);
        return new RootTransform(
            position,
            yaw,
            new Matrix4f().rotateY(rotationRadians),
            new Quaternionf().rotationY(rotationRadians)
        );
    }

    public Matrix4f displayMatrix(Matrix4fc nodeMatrix) {
        return new Matrix4f(this.rotationMatrix).mul(nodeMatrix);
    }

    public Transformation displayTransformation(PreparedEmote.PreparedTransform transform) {
        if (transform.preservesMatrix()) {
            return new Transformation(new Matrix4f(this.rotationMatrix).mul(transform.localMatrix()));
        }
        return new Transformation(
            this.rotation.transform(transform.translation(), new Vector3f()),
            new Quaternionf(this.rotation).mul(transform.leftRotation()),
            new Vector3f(transform.scale()),
            new Quaternionf(transform.rightRotation())
        );
    }

    public float relativeYaw(float currentYaw) {
        return Mth.wrapDegrees(currentYaw - this.yaw);
    }

    public Matrix4f worldMatrix(float currentYaw, Matrix4fc displayMatrix) {
        return new Matrix4f()
            .rotateY((float) Math.toRadians(-relativeYaw(currentYaw)))
            .mul(displayMatrix);
    }

}
