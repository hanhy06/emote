package io.github.hanhy06.emote.playback.runtime;

import com.mojang.math.Transformation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

public record RootTransform(Vec3 position, float yaw, Matrix4f rotationMatrix) {
    private static final float MODEL_FORWARD_YAW_OFFSET = 180.0F;

    public RootTransform {
        rotationMatrix = new Matrix4f(rotationMatrix);
    }

    public static RootTransform create(Vec3 position, float yaw) {
        float rotationRadians = (float) Math.toRadians(MODEL_FORWARD_YAW_OFFSET - yaw);
        return new RootTransform(
            position,
            yaw,
            new Matrix4f().rotateY(rotationRadians)
        );
    }

    public Matrix4f displayMatrix(Matrix4fc nodeMatrix) {
        return new Matrix4f(this.rotationMatrix).mul(nodeMatrix);
    }

    public Transformation displayTransformation(Matrix4fc matrix) {
        return new Transformation(displayMatrix(matrix));
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
