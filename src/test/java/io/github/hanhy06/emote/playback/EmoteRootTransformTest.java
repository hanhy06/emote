package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmoteRootTransformTest {
    @Test
    void convertsRowMajorTranslationToJomlMatrix() {
        EmoteAnimation.Matrix matrix = matrix(2.0D, 3.0D, 4.0D);

        var result = EmoteRootTransform.toJoml(matrix).transformPosition(new org.joml.Vector3f());

        assertEquals(2.0F, result.x, 0.0001F);
        assertEquals(3.0F, result.y, 0.0001F);
        assertEquals(4.0F, result.z, 0.0001F);
    }

    @Test
    void alignsModelForwardWithPlayerYaw() {
        assertForward(0.0F, 0.0F, 1.0F);
        assertForward(90.0F, -1.0F, 0.0F);
        assertForward(180.0F, 0.0F, -1.0F);
        assertForward(-90.0F, 1.0F, 0.0F);
    }

    @Test
    void calculatesWrappedYawRelativeToPlaybackStart() {
        EmoteRootTransform root = EmoteRootTransform.create(Vec3.ZERO, 170.0F);

        assertEquals(20.0F, root.relativeYaw(-170.0F), 0.0001F);
    }

    @Test
    void rotatesDisplayMatrixToCurrentPlayerYaw() {
        EmoteRootTransform root = EmoteRootTransform.create(Vec3.ZERO, 0.0F);
        Vector3f result = root.worldMatrix(90.0F, root.displayMatrix(matrix(0.0D, 0.0D, 0.0D)))
            .transformDirection(new Vector3f(0.0F, 0.0F, -1.0F));

        assertEquals(-1.0F, result.x, 0.0001F);
        assertEquals(0.0F, result.z, 0.0001F);
    }

    @Test
    void preparedDisplayTransformationMatchesMatrixComposition() {
        EmoteAnimation.Matrix matrix = new EmoteAnimation.Matrix(List.of(
            0.0D, -2.0D, 0.0D, 2.0D,
            1.0D, 0.0D, 0.0D, 3.0D,
            0.0D, 0.0D, 0.5D, 4.0D,
            0.0D, 0.0D, 0.0D, 1.0D
        ));
        EmoteRootTransform root = EmoteRootTransform.create(Vec3.ZERO, 37.0F);

        Matrix4fc expected = root.displayMatrix(matrix);
        Matrix4fc actual = root.displayTransformation(
            PlaybackPlan.PreparedTransform.create(matrix, false)
        ).getMatrix();

        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                assertEquals(expected.get(column, row), actual.get(column, row), 0.0001F);
            }
        }
    }

    private void assertForward(float yaw, float expectedX, float expectedZ) {
        Vector3f result = EmoteRootTransform.create(Vec3.ZERO, yaw)
            .displayMatrix(matrix(0.0D, 0.0D, 0.0D))
            .transformDirection(new Vector3f(0.0F, 0.0F, -1.0F));
        assertEquals(expectedX, result.x, 0.0001F);
        assertEquals(expectedZ, result.z, 0.0001F);
    }

    private EmoteAnimation.Matrix matrix(double x, double y, double z) {
        return new EmoteAnimation.Matrix(List.of(
            1.0D, 0.0D, 0.0D, x,
            0.0D, 1.0D, 0.0D, y,
            0.0D, 0.0D, 1.0D, z,
            0.0D, 0.0D, 0.0D, 1.0D
        ));
    }
}
