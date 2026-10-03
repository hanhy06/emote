package io.github.hanhy06.emote.api;

import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

public record PlaybackInfo(
    UUID sessionId,
    @Nullable UUID playerUuid,
    Identifier emoteId,
    PlaybackState state,
    long elapsedTicks,
    int timelineTick,
    PlaybackPosition position,
    PlaybackPlacement placement
) {
    public PlaybackInfo {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(emoteId, "emoteId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(placement, "placement");
    }
}
