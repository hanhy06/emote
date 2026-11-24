package io.github.hanhy06.emote.mixin;

import io.github.hanhy06.emote.playback.VirtualDisplayEntity;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Display.class)
abstract class DisplayMixin implements VirtualDisplayEntity {
    @Unique private boolean emote$virtualDisplay;

    @Override
    public void emote$markVirtualDisplay() {
        this.emote$virtualDisplay = true;
    }

    @Override
    public boolean emote$isVirtualDisplay() {
        return this.emote$virtualDisplay;
    }
}
