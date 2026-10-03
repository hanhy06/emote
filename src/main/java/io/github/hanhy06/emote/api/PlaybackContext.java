package io.github.hanhy06.emote.api;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

public interface PlaybackContext {
    UUID sessionId();
    String payload();
    MinecraftServer server();
    ServerLevel level();
    long elapsedTicks();
    @Nullable Integer animationTick();
    Optional<Entity> actor(String name);
    Optional<Entity> nodeEntity(String nodeId);
    Optional<Vec3> nodeWorldPosition(String nodeId);
    Vec3 rootPosition();
    Optional<PlaybackStopReason> stopReason();
    @Nullable Object userState();
    void setUserState(@Nullable Object state);
}
