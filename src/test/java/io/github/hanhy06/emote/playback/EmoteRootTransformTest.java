package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.animation.EmoteAnimation;
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

    private EmoteAnimation.Matrix matrix(double x, double y, double z) {
        return new EmoteAnimation.Matrix(List.of(
            1.0D, 0.0D, 0.0D, x,
            0.0D, 1.0D, 0.0D, y,
            0.0D, 0.0D, 1.0D, z,
            0.0D, 0.0D, 0.0D, 1.0D
        ));
    }
}
