package io.github.hanhy06.emote.molang;

import team.unnamed.mocha.runtime.value.MutableObjectBinding;
import team.unnamed.mocha.runtime.value.NumberValue;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleUnaryOperator;

public final class MolangMath extends MutableObjectBinding {
    public static final MolangMath INSTANCE = new MolangMath();

    private static final double DEGREES_TO_RADIANS = Math.PI / 180.0D;
    private static final double BACK_OVERSHOOT = 1.70158D;

    private MolangMath() {
        this.setFunction("abs", Math::abs);
        this.setFunction("acos", value -> Math.acos(value) / DEGREES_TO_RADIANS);
        this.setFunction("asin", value -> Math.asin(value) / DEGREES_TO_RADIANS);
        this.setFunction("atan", value -> Math.atan(value) / DEGREES_TO_RADIANS);
        this.setFunction("atan2", (y, x) -> Math.atan2(y, x) / DEGREES_TO_RADIANS);
        this.setFunction("ceil", Math::ceil);
        this.setFunction("clamp", (value, min, max) -> Math.max(min, Math.min(max, value)));
        this.setFunction("copy_sign", Math::copySign);
        this.setFunction("cos", value -> Math.cos(value * DEGREES_TO_RADIANS));
        this.setFunction("die_roll", MolangMath::dieRoll);
        this.setFunction("die_roll_integer", MolangMath::dieRollInteger);

        bindEasing("back", MolangMath::easeInBack);
        bindEasing("bounce", MolangMath::easeInBounce);
        bindEasing("circ", MolangMath::easeInCirc);
        bindEasing("cubic", value -> value * value * value);
        bindEasing("elastic", MolangMath::easeInElastic);
        bindEasing("expo", MolangMath::easeInExpo);
        bindEasing("quad", value -> value * value);
        bindEasing("quart", value -> value * value * value * value);
        bindEasing("quint", value -> value * value * value * value * value);
        bindEasing("sine", value -> 1.0D - Math.cos(value * Math.PI / 2.0D));

        this.setFunction("exp", Math::exp);
        this.setFunction("floor", Math::floor);
        this.setFunction("hermite_blend", value -> value * value * (3.0D - 2.0D * value));
        this.setFunction("inverse_lerp", (start, end, value) -> (value - start) / (end - start));
        this.setFunction("lerp", MolangMath::lerp);
        this.setFunction("lerprotate", MolangMath::lerpRotate);
        this.setFunction("ln", Math::log);
        this.setFunction("max", Math::max);
        this.setFunction("min", Math::min);
        this.setFunction("min_angle", MolangMath::minAngle);
        this.setFunction("mod", (value, denominator) -> value % denominator);
        this.set("pi", NumberValue.of(Math.PI));
        this.setFunction("pow", Math::pow);
        this.setFunction("random", MolangMath::random);
        this.setFunction("random_integer", MolangMath::randomInteger);
        this.setFunction("round", Math::round);
        this.setFunction("sign", value -> value > 0.0D ? 1.0D : -1.0D);
        this.setFunction("sin", value -> Math.sin(value * DEGREES_TO_RADIANS));
        this.setFunction("sqrt", Math::sqrt);
        this.setFunction("trunc", value -> value - value % 1.0D);
        this.block();
    }

    private void bindEasing(String name, DoubleUnaryOperator easeIn) {
        this.setFunction("ease_in_" + name, (start, end, progress) -> lerp(start, end, easeIn.applyAsDouble(progress)));
        this.setFunction("ease_out_" + name, (start, end, progress) -> lerp(start, end, 1.0D - easeIn.applyAsDouble(1.0D - progress)));
        this.setFunction("ease_in_out_" + name, (start, end, progress) -> {
            double eased = progress < 0.5D
                ? easeIn.applyAsDouble(progress * 2.0D) / 2.0D
                : 1.0D - easeIn.applyAsDouble((1.0D - progress) * 2.0D) / 2.0D;
            return lerp(start, end, eased);
        });
    }

    private static double dieRoll(double count, double low, double high) {
        if (!validRandomRange(low, high)) {
            return low == high ? low * normalizedRollCount(count) : 0.0D;
        }
        int rolls = normalizedRollCount(count);
        double result = 0.0D;
        for (int index = 0; index < rolls; index++) {
            result += random(low, high);
        }
        return result;
    }

    private static double dieRollInteger(double count, double low, double high) {
        int rolls = normalizedRollCount(count);
        double result = 0.0D;
        for (int index = 0; index < rolls; index++) {
            result += randomInteger(low, high);
        }
        return result;
    }

    private static int normalizedRollCount(double count) {
        if (!Double.isFinite(count) || count <= 0.0D) {
            return 0;
        }
        return (int) Math.min(Math.ceil(count), Integer.MAX_VALUE);
    }

    private static double random(double low, double high) {
        if (!validRandomRange(low, high)) {
            return low == high ? low : 0.0D;
        }
        double exclusiveUpperBound = Math.nextUp(high);
        if (!Double.isFinite(exclusiveUpperBound)) {
            exclusiveUpperBound = high;
        }
        return ThreadLocalRandom.current().nextDouble(low, exclusiveUpperBound);
    }

    private static double randomInteger(double low, double high) {
        if (!Double.isFinite(low) || !Double.isFinite(high)) {
            return 0.0D;
        }
        long first = (long) Math.ceil(low);
        long last = (long) Math.floor(high);
        if (first > last) {
            return 0.0D;
        }
        if (first == last) {
            return first;
        }
        if (last == Long.MAX_VALUE) {
            double offset = Math.floor(ThreadLocalRandom.current().nextDouble() * ((double) last - first + 1.0D));
            return Math.min((double) last, first + offset);
        }
        return ThreadLocalRandom.current().nextLong(first, last + 1L);
    }

    private static boolean validRandomRange(double low, double high) {
        return Double.isFinite(low) && Double.isFinite(high) && low < high;
    }

    private static double lerp(double start, double end, double progress) {
        return start + (end - start) * progress;
    }

    private static double lerpRotate(double start, double end, double progress) {
        return start + minAngle(end - start) * progress;
    }

    private static double minAngle(double angle) {
        double result = angle % 360.0D;
        if (result >= 180.0D) {
            result -= 360.0D;
        } else if (result < -180.0D) {
            result += 360.0D;
        }
        return result;
    }

    private static double easeInBack(double value) {
        return value * value * ((BACK_OVERSHOOT + 1.0D) * value - BACK_OVERSHOOT);
    }

    private static double easeInBounce(double value) {
        return 1.0D - easeOutBounce(1.0D - value);
    }

    private static double easeOutBounce(double value) {
        double scaled = value;
        if (scaled < 1.0D / 2.75D) {
            return 7.5625D * scaled * scaled;
        }
        if (scaled < 2.0D / 2.75D) {
            scaled -= 1.5D / 2.75D;
            return 7.5625D * scaled * scaled + 0.75D;
        }
        if (scaled < 2.5D / 2.75D) {
            scaled -= 2.25D / 2.75D;
            return 7.5625D * scaled * scaled + 0.9375D;
        }
        scaled -= 2.625D / 2.75D;
        return 7.5625D * scaled * scaled + 0.984375D;
    }

    private static double easeInCirc(double value) {
        return 1.0D - Math.sqrt(1.0D - value * value);
    }

    private static double easeInExpo(double value) {
        return value == 0.0D ? 0.0D : Math.pow(2.0D, 10.0D * (value - 1.0D));
    }

    private static double easeInElastic(double value) {
        if (value == 0.0D || value == 1.0D) {
            return value;
        }
        double period = 0.3D;
        double phase = period / 4.0D;
        double shifted = value - 1.0D;
        return -Math.pow(2.0D, 10.0D * shifted) * Math.sin((shifted - phase) * 2.0D * Math.PI / period);
    }
}
