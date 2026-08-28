package io.github.hanhy06.emote.mixin.accessor;

import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SynchedEntityData.class)
public interface SynchedEntityDataAccessor {
    @Accessor("itemsById")
    SynchedEntityData.DataItem<?>[] emote$getItemsById();
}
