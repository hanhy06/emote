package io.github.hanhy06.emote.api;

import net.minecraft.resources.Identifier;

import java.util.Objects;
import java.util.UUID;

public record PlaybackInfo(UUID playerUuid, Identifier emoteId, int currentTick) {
    public PlaybackInfo {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(emoteId, "emoteId");
    }
}
