package io.github.hanhy06.emote.mixin;

import com.mojang.math.Transformation;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Display.class)
public interface DisplayAccessor {
    @Invoker("setTransformation")
    void emote$setTransformation(Transformation transformation);

    @Invoker("setTransformationInterpolationDuration")
    void emote$setTransformationInterpolationDuration(int duration);

    @Invoker("setTransformationInterpolationDelay")
    void emote$setTransformationInterpolationDelay(int ticks);
}
