package io.github.hanhy06.emote.api;

import net.minecraft.resources.Identifier;

public interface EmoteRegistration {
    Identifier id();

    boolean isRegistered();

    boolean unregister();
}
