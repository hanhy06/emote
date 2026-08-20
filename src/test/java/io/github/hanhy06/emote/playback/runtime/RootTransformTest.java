package io.github.hanhy06.emote.playback.runtime;

import io.github.hanhy06.emote.content.PreparedAnimation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RootTransformTest {
    @Test
    void alignsModelForwardWithPlayerYaw() {
        assertForward(0.0F, 0.0F, 1.0F);
        assertForward(90.0F, -1.0F, 0.0F);
        assertForward(180.0F, 0.0F, -1.0F);
        assertForward(-90.0F, 1.0F, 0.0F);
    }

    @Test
    void calculatesWrappedYawRelativeToPlaybackStart() {
        RootTransform root = RootTransform.create(Vec3.ZERO, 170.0F);

        assertEquals(20.0F, root.relativeYaw(-170.0F), 0.0001F);
    }

    @Test
    void rotatesDisplayMatrixToCurrentPlayerYaw() {
        RootTransform root = RootTransform.create(Vec3.ZERO, 0.0F);
        Vector3f result = root.worldMatrix(90.0F, root.displayMatrix(matrix(0.0D, 0.0D, 0.0D)))
            .transformDirection(new Vector3f(0.0F, 0.0F, -1.0F));

        assertEquals(-1.0F, result.x, 0.0001F);
        assertEquals(0.0F, result.z, 0.0001F);
    }

    @Test
    void preparedDisplayTransformationMatchesMatrixComposition() {
        Matrix4f matrix = new Matrix4f(
            0.0F, 1.0F, 0.0F, 0.0F,
            -2.0F, 0.0F, 0.0F, 0.0F,
            0.0F, 0.0F, 0.5F, 0.0F,
            2.0F, 3.0F, 4.0F, 1.0F
        );
        RootTransform root = RootTransform.create(Vec3.ZERO, 37.0F);

        Matrix4fc expected = root.displayMatrix(matrix);
        Matrix4fc actual = root.displayTransformation(
            PreparedAnimation.PreparedTransform.create(matrix, false)
        ).getMatrix();

        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                assertEquals(expected.get(column, row), actual.get(column, row), 0.0001F);
            }
        }
    }

    private void assertForward(float yaw, float expectedX, float expectedZ) {
        Vector3f result = RootTransform.create(Vec3.ZERO, yaw)
            .displayMatrix(matrix(0.0D, 0.0D, 0.0D))
            .transformDirection(new Vector3f(0.0F, 0.0F, -1.0F));
        assertEquals(expectedX, result.x, 0.0001F);
        assertEquals(expectedZ, result.z, 0.0001F);
    }

    private Matrix4f matrix(double x, double y, double z) {
        return new Matrix4f().translate((float) x, (float) y, (float) z);
    }
}
