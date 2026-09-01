package io.github.hanhy06.emote.api;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

public record EmoteCallbackEvent(
    ServerPlayer player,
    Identifier playbackId,
    Identifier animationId,
    int playbackTick,
    int animationTick,
    EmoteCallbackPhase phase,
    ParticipantRole participant,
    Vec3 origin,
    Identifier name,
    String payload
) {
    public EmoteCallbackEvent {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(playbackId, "playbackId");
        Objects.requireNonNull(animationId, "animationId");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(participant, "participant");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(payload, "payload");
    }
}
