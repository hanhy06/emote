package io.github.hanhy06.emote.animation;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;

import java.util.ArrayList;
import java.util.List;

final class EmoteMatrixNormalizer {
    private static final double SHEAR_EPSILON = 1.0E-6D;
    private static final double SINGULAR_EPSILON = 1.0E-12D;
    private static final double POLAR_CONVERGENCE_EPSILON = 1.0E-10D;
    private static final int MAX_POLAR_ITERATIONS = 16;

    private EmoteMatrixNormalizer() {
    }

    static EmoteAnimation.Matrix stabilize(EmoteAnimation.Matrix matrix) {
        double[] linear = {
            matrix.value(0), matrix.value(1), matrix.value(2),
            matrix.value(4), matrix.value(5), matrix.value(6),
            matrix.value(8), matrix.value(9), matrix.value(10)
        };
        if (maximumColumnShear(linear) <= SHEAR_EPSILON) {
            return matrix;
        }

        double[] rotation = polarRotation(linear);
        if (rotation == null) {
            return matrix;
        }
        double[] scales = {
            rotation[0] * linear[0] + rotation[3] * linear[3] + rotation[6] * linear[6],
            rotation[1] * linear[1] + rotation[4] * linear[4] + rotation[7] * linear[7],
            rotation[2] * linear[2] + rotation[5] * linear[5] + rotation[8] * linear[8]
        };
        if (determinant3(rotation) < 0.0D) {
            int axis = smallestAbsoluteIndex(scales);
            for (int row = 0; row < 3; row++) {
                rotation[row * 3 + axis] *= -1.0D;
            }
            scales[axis] *= -1.0D;
        }

        List<Double> values = new ArrayList<>(matrix.values());
        values.set(0, rotation[0] * scales[0]);
        values.set(1, rotation[1] * scales[1]);
        values.set(2, rotation[2] * scales[2]);
        values.set(4, rotation[3] * scales[0]);
        values.set(5, rotation[4] * scales[1]);
        values.set(6, rotation[5] * scales[2]);
        values.set(8, rotation[6] * scales[0]);
        values.set(9, rotation[7] * scales[1]);
        values.set(10, rotation[8] * scales[2]);
        return new EmoteAnimation.Matrix(values);
    }

    private static double[] polarRotation(double[] linear) {
        double[] current = linear.clone();
        for (int iteration = 0; iteration < MAX_POLAR_ITERATIONS; iteration++) {
            double[] inverseTranspose = inverseTranspose3(current);
            if (inverseTranspose == null) {
                return null;
            }
            double[] next = new double[9];
            double difference = 0.0D;
            for (int index = 0; index < next.length; index++) {
                next[index] = (current[index] + inverseTranspose[index]) * 0.5D;
                difference = Math.max(difference, Math.abs(next[index] - current[index]));
            }
            current = next;
            if (difference <= POLAR_CONVERGENCE_EPSILON) {
                break;
            }
        }
        return current;
    }

    private static double[] inverseTranspose3(double[] matrix) {
        double a = matrix[0];
        double b = matrix[1];
        double c = matrix[2];
        double d = matrix[3];
        double e = matrix[4];
        double f = matrix[5];
        double g = matrix[6];
        double h = matrix[7];
        double i = matrix[8];
        double determinant = determinant3(matrix);
        if (Math.abs(determinant) <= SINGULAR_EPSILON) {
            return null;
        }
        return new double[] {
            (e * i - f * h) / determinant,
            (f * g - d * i) / determinant,
            (d * h - e * g) / determinant,
            (c * h - b * i) / determinant,
            (a * i - c * g) / determinant,
            (b * g - a * h) / determinant,
            (b * f - c * e) / determinant,
            (c * d - a * f) / determinant,
            (a * e - b * d) / determinant
        };
    }

    private static double determinant3(double[] matrix) {
        double a = matrix[0];
        double b = matrix[1];
        double c = matrix[2];
        double d = matrix[3];
        double e = matrix[4];
        double f = matrix[5];
        double g = matrix[6];
        double h = matrix[7];
        double i = matrix[8];
        return a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
    }

    private static double maximumColumnShear(double[] matrix) {
        double[][] columns = {
            {matrix[0], matrix[3], matrix[6]},
            {matrix[1], matrix[4], matrix[7]},
            {matrix[2], matrix[5], matrix[8]}
        };
        double[] lengths = new double[3];
        for (int index = 0; index < columns.length; index++) {
            lengths[index] = Math.sqrt(dot(columns[index], columns[index]));
            if (lengths[index] <= SINGULAR_EPSILON) {
                return 0.0D;
            }
        }

        double maximum = 0.0D;
        for (int first = 0; first < columns.length; first++) {
            for (int second = first + 1; second < columns.length; second++) {
                maximum = Math.max(
                    maximum,
                    Math.abs(dot(columns[first], columns[second]) / (lengths[first] * lengths[second]))
                );
            }
        }
        return maximum;
    }

    private static double dot(double[] first, double[] second) {
        return first[0] * second[0] + first[1] * second[1] + first[2] * second[2];
    }

    private static int smallestAbsoluteIndex(double[] values) {
        int result = 0;
        for (int index = 1; index < values.length; index++) {
            if (Math.abs(values[index]) < Math.abs(values[result])) {
                result = index;
            }
        }
        return result;
    }
}
