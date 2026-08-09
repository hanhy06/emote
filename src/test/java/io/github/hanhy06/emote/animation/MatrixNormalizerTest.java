package io.github.hanhy06.emote.animation;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class MatrixNormalizerTest {
    @Test
    void removesShearAndPreservesTranslation() {
        EmoteAnimation.Matrix result = MatrixNormalizer.stabilize(matrix(List.of(
            1.0D, 0.25D, 0.0D, 4.0D,
            0.0D, 1.0D, 0.2D, 5.0D,
            0.0D, 0.0D, 0.5D, 6.0D,
            0.0D, 0.0D, 0.0D, 1.0D
        )));

        assertEquals(4.0D, result.value(3));
        assertEquals(5.0D, result.value(7));
        assertEquals(6.0D, result.value(11));
        assertEquals(0.0D, normalizedColumnDot(result, 0, 1), 1.0E-8D);
        assertEquals(0.0D, normalizedColumnDot(result, 0, 2), 1.0E-8D);
        assertEquals(0.0D, normalizedColumnDot(result, 1, 2), 1.0E-8D);
    }

    @Test
    void retainsAlreadyStableTrsMatrix() {
        EmoteAnimation.Matrix matrix = matrix(List.of(
            0.0D, -2.0D, 0.0D, 1.0D,
            3.0D, 0.0D, 0.0D, 2.0D,
            0.0D, 0.0D, 4.0D, 3.0D,
            0.0D, 0.0D, 0.0D, 1.0D
        ));

        assertSame(matrix, MatrixNormalizer.stabilize(matrix));
    }

    private EmoteAnimation.Matrix matrix(List<Double> values) {
        return new EmoteAnimation.Matrix(values);
    }

    private double normalizedColumnDot(EmoteAnimation.Matrix matrix, int first, int second) {
        double dot = 0.0D;
        double firstLengthSquared = 0.0D;
        double secondLengthSquared = 0.0D;
        for (int row = 0; row < 3; row++) {
            double firstValue = matrix.value(row * 4 + first);
            double secondValue = matrix.value(row * 4 + second);
            dot += firstValue * secondValue;
            firstLengthSquared += firstValue * firstValue;
            secondLengthSquared += secondValue * secondValue;
        }
        return dot / Math.sqrt(firstLengthSquared * secondLengthSquared);
    }
}
