package io.github.hanhy06.emote.playback;

import net.minecraft.world.entity.Entity;

public interface VirtualDisplayEntity {
    void emote$markVirtualDisplay();
    boolean emote$isVirtualDisplay();

    static boolean isVirtual(Entity entity) {
        return entity instanceof VirtualDisplayEntity display && display.emote$isVirtualDisplay();
    }
}
