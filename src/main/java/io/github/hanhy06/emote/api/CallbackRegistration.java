package io.github.hanhy06.emote.api;

import net.minecraft.resources.Identifier;

public interface CallbackRegistration {
    Identifier id();

    boolean isRegistered();

    boolean unregister();
}
