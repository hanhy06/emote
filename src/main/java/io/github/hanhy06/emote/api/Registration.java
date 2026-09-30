package io.github.hanhy06.emote.api;

import net.minecraft.resources.Identifier;

public interface Registration {
    Identifier id();

    boolean isRegistered();

    boolean unregister();
}
