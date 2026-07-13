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
	Vec3 startPosition,
	boolean playerVisibilityManaged,
	boolean wasInvisible
) {
	public ActiveEmote {
		Objects.requireNonNull(playerUuid, "playerUuid");
		Objects.requireNonNull(levelKey, "levelKey");
		Objects.requireNonNull(namespace, "namespace");
		Objects.requireNonNull(startPosition, "startPosition");
	}
}
