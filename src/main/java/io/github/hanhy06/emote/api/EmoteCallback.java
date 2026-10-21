package io.github.hanhy06.emote.api;

import net.minecraft.resources.Identifier;
import java.util.Objects;

public record EmoteCallback(Identifier name, String payload) {
    public EmoteCallback {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(payload, "payload");
    }
}
