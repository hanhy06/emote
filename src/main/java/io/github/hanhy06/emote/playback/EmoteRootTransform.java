package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.animation.EmoteAnimation.Matrix;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public record EmoteRootTransform(Vec3 position, float yaw, Matrix4f rotationMatrix) {
    public EmoteRootTransform {
        rotationMatrix = new Matrix4f(rotationMatrix);
    }

    public static EmoteRootTransform fromPlayer(ServerPlayer player) {
        return create(player.position(), player.getYRot());
    }

    public static EmoteRootTransform create(Vec3 position, float yaw) {
        Matrix4f rotation = new Matrix4f().rotateY((float)Math.toRadians(-yaw));
        return new EmoteRootTransform(position, yaw, rotation);
    }

    public Matrix4f displayMatrix(Matrix nodeMatrix) {
        return new Matrix4f(this.rotationMatrix).mul(toJoml(nodeMatrix));
    }

    public Matrix4f worldMatrix(Matrix nodeMatrix) {
        return new Matrix4f()
            .translation((float)this.position.x, (float)this.position.y, (float)this.position.z)
            .mul(this.rotationMatrix)
            .mul(toJoml(nodeMatrix));
    }

    public Vec3 transformPosition(Matrix nodeMatrix, io.github.hanhy06.emote.animation.EmoteAnimation.Vec3 offset) {
        Vector3f transformed = worldMatrix(nodeMatrix).transformPosition(
            new Vector3f((float)offset.x(), (float)offset.y(), (float)offset.z())
        );
        return new Vec3(transformed.x, transformed.y, transformed.z);
    }

    static Matrix4f toJoml(Matrix matrix) {
        return new Matrix4f(
            (float)matrix.value(0), (float)matrix.value(4), (float)matrix.value(8), (float)matrix.value(12),
            (float)matrix.value(1), (float)matrix.value(5), (float)matrix.value(9), (float)matrix.value(13),
            (float)matrix.value(2), (float)matrix.value(6), (float)matrix.value(10), (float)matrix.value(14),
            (float)matrix.value(3), (float)matrix.value(7), (float)matrix.value(11), (float)matrix.value(15)
        );
    }
}
