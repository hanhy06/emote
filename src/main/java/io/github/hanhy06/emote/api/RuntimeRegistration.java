package io.github.hanhy06.emote.api;

import net.minecraft.resources.Identifier;

public interface RuntimeRegistration {
    Identifier id();

    boolean isRegistered();

    boolean unregister();
}
