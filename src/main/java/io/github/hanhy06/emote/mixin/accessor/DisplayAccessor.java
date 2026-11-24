package io.github.hanhy06.emote.mixin.accessor;

import com.mojang.math.Transformation;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Display;
import org.joml.Quaternionfc;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Display.class)
public interface DisplayAccessor {
    @Accessor("DATA_TRANSLATION_ID")
    static EntityDataAccessor<Vector3fc> emote$translation() { throw new AssertionError(); }

    @Accessor("DATA_SCALE_ID")
    static EntityDataAccessor<Vector3fc> emote$scale() { throw new AssertionError(); }

    @Accessor("DATA_LEFT_ROTATION_ID")
    static EntityDataAccessor<Quaternionfc> emote$leftRotation() { throw new AssertionError(); }

    @Accessor("DATA_RIGHT_ROTATION_ID")
    static EntityDataAccessor<Quaternionfc> emote$rightRotation() { throw new AssertionError(); }

    @Accessor("DATA_TRANSFORMATION_INTERPOLATION_START_DELTA_TICKS_ID")
    static EntityDataAccessor<Integer> emote$interpolationStart() { throw new AssertionError(); }

    @Accessor("DATA_TRANSFORMATION_INTERPOLATION_DURATION_ID")
    static EntityDataAccessor<Integer> emote$interpolationDuration() { throw new AssertionError(); }

    @Accessor("DATA_POS_ROT_INTERPOLATION_DURATION_ID")
    static EntityDataAccessor<Integer> emote$posRotInterpolationDuration() { throw new AssertionError(); }

    @Invoker("setTransformation")
    void emote$setTransformation(Transformation transformation);

    @Invoker("setTransformationInterpolationDuration")
    void emote$setTransformationInterpolationDuration(int ticks);

    @Invoker("setTransformationInterpolationDelay")
    void emote$setTransformationInterpolationDelay(int ticks);

    @Invoker("setPosRotInterpolationDuration")
    void emote$setPosRotInterpolationDuration(int ticks);
}
