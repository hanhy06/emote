package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.animation.EmoteAnimation;
import net.minecraft.world.phys.Vec3;
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
    void rootLocalPositiveZUsesPlayersHorizontalLookDirection() {
        EmoteRootTransform root = EmoteRootTransform.create(new Vec3(10.0D, 20.0D, 30.0D), 90.0F);

        Vec3 result = root.transformPosition(matrix(0.0D, 0.0D, 0.0D), new EmoteAnimation.Vec3(0.0D, 0.0D, 1.0D));

        assertEquals(9.0D, result.x, 0.0001D);
        assertEquals(20.0D, result.y, 0.0001D);
        assertEquals(30.0D, result.z, 0.0001D);
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
