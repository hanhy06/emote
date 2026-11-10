package io.github.hanhy06.emote.api;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

public interface Playback {
    UUID getId();
    Identifier getEmoteId();
    EmoteInfo getEmote();
    PlaybackState getState();
    boolean isActive();
    Optional<PlaybackStopReason> getStopReason();
    long getElapsedTicks();
    @Nullable Integer getTick();
    @Nullable Integer getAnimationTick();
    @Nullable PlaybackPosition getPosition();
    @Nullable PlaybackTimeline getTimeline();
    MinecraftServer getServer();
    ServerLevel getWorld();
    PlaybackPlacement getPlacement();
    Vec3 getRootPosition();
    Optional<Entity> getActor();
    Optional<Entity> getActor(String name);
    Optional<Entity> getAttachmentEntity(String nodeId, String attachmentId);
    Optional<Vec3> getNodeWorldPosition(String nodeId);
    boolean stop();
    boolean stop(PlaybackStopReason reason);
    boolean setTick(int tick);
    boolean setAnimationTick(int tick);
    boolean setStep(int stepIndex, int repeatIndex, int tick);
    boolean setPlacement(PlaybackPlacement placement);
    PlaybackInfo snapshot();
}
