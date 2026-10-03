package io.github.hanhy06.emote.api;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

public interface PlaybackContext {
    UUID getSessionId();
    String getPayload();
    MinecraftServer getServer();
    ServerLevel getWorld();
    long getElapsedTicks();
    int getTick();
    @Nullable Integer getAnimationTick();
    boolean setTick(int tick);
    boolean setAnimationTick(int tick);
    boolean setStep(int stepIndex, int repeatIndex, int tick);
    Optional<Entity> getActor(String name);
    Optional<Entity> getNodeEntity(String nodeId);
    Optional<Vec3> getNodeWorldPosition(String nodeId);
    Vec3 getRootPosition();
    Optional<PlaybackStopReason> getStopReason();
    @Nullable Object getUserState();
    void setUserState(@Nullable Object state);
}
