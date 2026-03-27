package io.github.hanhy06.emot.playback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class EmotePlaybackManager {
	private static final long TICKS_PER_KEYFRAME = 2L;
	private static final long PLAYBACK_BUFFER_TICKS = 8L;
	private static final double MOVE_STOP_DISTANCE_SQUARED = 0.0004D;
	private final Map<UUID, ActiveEmote> activeEmoteMap = new ConcurrentHashMap<>();

	public void startEmote(ServerPlayer player, String namespace, String animationName, int keyframeCount) {
		MinecraftServer server = player.level().getServer();
		stopMatchingNamespaceEmotes(server, player.getUUID(), namespace);
		this.stopEmote(player);

		executeFunction(player, namespace + ":_/create");
		executeFunction(player, namespace + ":a/" + animationName + "/play_anim");
		boolean wasInvisible = player.isInvisible();
		player.setInvisible(true);

		long stopTick = server.getTickCount() + calculatePlaybackTicks(keyframeCount);
		ActiveEmote activeEmote = new ActiveEmote(
			player.getUUID(),
			player.level().dimension(),
			namespace,
			animationName,
			player.position(),
			stopTick,
			wasInvisible
		);
		this.activeEmoteMap.put(player.getUUID(), activeEmote);
	}

	public Optional<ActiveEmote> stopEmote(ServerPlayer player) {
		return stopEmote(player.level().getServer(), player.getUUID());
	}

	public Optional<ActiveEmote> stopEmote(MinecraftServer server, UUID playerUuid) {
		ActiveEmote activeEmote = this.activeEmoteMap.remove(playerUuid);
		if (activeEmote == null) {
			return Optional.empty();
		}

		stopActiveEmote(server, activeEmote);
		return Optional.of(activeEmote);
	}

	public Optional<ActiveEmote> findActiveEmote(UUID playerUuid) {
		return Optional.ofNullable(this.activeEmoteMap.get(playerUuid));
	}

	public void tick(MinecraftServer server) {
		if (this.activeEmoteMap.isEmpty()) {
			return;
		}

		long currentTick = server.getTickCount();
		List<UUID> playerUuidListToStop = new ArrayList<>();

		for (ActiveEmote activeEmote : this.activeEmoteMap.values()) {
			ServerPlayer player = server.getPlayerList().getPlayer(activeEmote.playerUuid());
			if (player == null || !player.isAlive() || !player.level().dimension().equals(activeEmote.levelKey()) || currentTick >= activeEmote.stopTick()) {
				playerUuidListToStop.add(activeEmote.playerUuid());
				continue;
			}

			if (hasMoved(player.position(), activeEmote.startPosition())) {
				playerUuidListToStop.add(activeEmote.playerUuid());
				player.sendSystemMessage(Component.literal("Emote stopped because you moved."));
			}
		}

		for (UUID playerUuid : playerUuidListToStop) {
			stopEmote(server, playerUuid);
		}
	}

	public void stopAllEmotes(MinecraftServer server) {
		List<UUID> playerUuidList = List.copyOf(this.activeEmoteMap.keySet());
		for (UUID playerUuid : playerUuidList) {
			stopEmote(server, playerUuid);
		}
	}

	private void stopMatchingNamespaceEmotes(MinecraftServer server, UUID playerUuid, String namespace) {
		List<UUID> playerUuidListToStop = this.activeEmoteMap.values().stream()
			.filter(activeEmote -> !activeEmote.playerUuid().equals(playerUuid))
			.filter(activeEmote -> activeEmote.namespace().equals(namespace))
			.map(ActiveEmote::playerUuid)
			.toList();

		for (UUID otherPlayerUuid : playerUuidListToStop) {
			stopEmote(server, otherPlayerUuid);
		}
	}

	private void stopActiveEmote(MinecraftServer server, ActiveEmote activeEmote) {
		executeFunction(server, activeEmote, activeEmote.namespace() + ":_/stop_anim");
		executeFunction(server, activeEmote, activeEmote.namespace() + ":_/delete");

		ServerPlayer player = server.getPlayerList().getPlayer(activeEmote.playerUuid());
		if (player != null) {
			player.setInvisible(activeEmote.wasInvisible());
		}
	}

	private long calculatePlaybackTicks(int keyframeCount) {
		return Math.max(20L, (long) keyframeCount * TICKS_PER_KEYFRAME + PLAYBACK_BUFFER_TICKS);
	}

	private boolean hasMoved(Vec3 currentPosition, Vec3 startPosition) {
		return currentPosition.distanceToSqr(startPosition) > MOVE_STOP_DISTANCE_SQUARED;
	}

	private void executeFunction(ServerPlayer player, String functionId) {
		CommandSourceStack source = player.createCommandSourceStack()
			.withMaximumPermission(LevelBasedPermissionSet.OWNER)
			.withSuppressedOutput();
		player.level().getServer().getCommands().performPrefixedCommand(source, "function " + functionId);
	}

	private void executeFunction(MinecraftServer server, ActiveEmote activeEmote, String functionId) {
		ServerLevel level = server.getLevel(activeEmote.levelKey());
		if (level == null) {
			return;
		}

		CommandSourceStack source = server.createCommandSourceStack()
			.withLevel(level)
			.withMaximumPermission(LevelBasedPermissionSet.OWNER)
			.withSuppressedOutput();
		server.getCommands().performPrefixedCommand(source, "function " + functionId);
	}
}
