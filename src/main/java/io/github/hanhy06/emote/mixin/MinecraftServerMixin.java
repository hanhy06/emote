package io.github.hanhy06.emote.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.hanhy06.emote.network.NetworkBenchmark;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;

import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
abstract class MinecraftServerMixin {
    @WrapMethod(method = "tickServer")
    private void emote$measureFullTick(BooleanSupplier haveTime, Operation<Void> original) {
        NetworkBenchmark benchmark = NetworkBenchmark.INSTANCE;
        if (!benchmark.isRunning()) {
            original.call(haveTime);
            return;
        }

        // Include Fabric's end-tick callbacks, which run after vanilla records its tick time.
        long started = System.nanoTime();
        original.call(haveTime);
        benchmark.sampleTick((MinecraftServer) (Object) this, System.nanoTime() - started);
    }
}
