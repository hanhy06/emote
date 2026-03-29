package io.github.hanhy06.emote.playback.data;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.UUID;

public record ActiveEmote(
	UUID playerUuid,
	ResourceKey<Level> levelKey,
	String namespace,
	String animationName,
	Vec3 startPosition,
	long stopTick,
	boolean wasInvisible
) {
	public ActiveEmote {
		Objects.requireNonNull(playerUuid, "playerUuid");
		Objects.requireNonNull(levelKey, "levelKey");
		Objects.requireNonNull(namespace, "namespace");
		Objects.requireNonNull(animationName, "animationName");
		Objects.requireNonNull(startPosition, "startPosition");
	}
}
