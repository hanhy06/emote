package io.github.hanhy06.emot.playback;

import io.github.hanhy06.emot.emote.EmoteAnimation;
import io.github.hanhy06.emot.emote.EmoteDefinition;
import io.github.hanhy06.emot.skin.PlayerSkinManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
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
	private static final double MOVE_STOP_HORIZONTAL_DISTANCE_SQUARED = 0.01D;
	private static final double MOVE_STOP_VERTICAL_DISTANCE = 0.12D;
	private static final double ROOT_SEARCH_DISTANCE = 8.0D;
	private final Map<UUID, ActiveEmote> activeEmoteMap = new ConcurrentHashMap<>();
	private final PlayerSkinManager playerSkinManager;

	public EmotePlaybackManager(PlayerSkinManager playerSkinManager) {
		this.playerSkinManager = playerSkinManager;
	}

	public Optional<String> startEmote(ServerPlayer player, EmoteDefinition definition, EmoteAnimation animation) {
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return Optional.of("Play failed.");
		}

		String namespace = definition.namespace();
		String animationName = animation.name();
		String createFunctionId = namespace + ":_/create";
		String playFunctionId = namespace + ":a/" + animationName + "/play_anim";
		if (!isLoadedFunction(server, createFunctionId) || !isLoadedFunction(server, playFunctionId)) {
			return Optional.of("Datapack not loaded.");
		}

		stopMatchingNamespaceEmotes(server, player.getUUID(), namespace);
		this.stopEmote(player);
		cleanupNamespace(player, namespace);

		executeFunction(player, createFunctionId);
		alignRootWithPlayer(player, namespace);
		this.playerSkinManager.applyPlayerSkin(player, definition);
		executeFunction(player, playFunctionId);
		boolean wasInvisible = player.isInvisible();
		player.setInvisible(true);

		long stopTick = server.getTickCount() + calculatePlaybackTicks(animation.keyframeCount());
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
		return Optional.empty();
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
				player.sendSystemMessage(Component.literal("Stop: moved"));
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
		double xDistance = currentPosition.x - startPosition.x;
		double zDistance = currentPosition.z - startPosition.z;
		double horizontalDistanceSquared = xDistance * xDistance + zDistance * zDistance;
		double verticalDistance = Math.abs(currentPosition.y - startPosition.y);
		return horizontalDistanceSquared > MOVE_STOP_HORIZONTAL_DISTANCE_SQUARED || verticalDistance > MOVE_STOP_VERTICAL_DISTANCE;
	}

	private boolean isLoadedFunction(MinecraftServer server, String functionId) {
		Identifier identifier = Identifier.tryParse(functionId);
		return identifier != null && server.getFunctions().get(identifier).isPresent();
	}

	private void cleanupNamespace(ServerPlayer player, String namespace) {
		MinecraftServer server = player.level().getServer();
		if (server == null || !isLoadedFunction(server, namespace + ":_/delete")) {
			return;
		}

		executeFunction(player, namespace + ":_/delete");
	}

	private void alignRootWithPlayer(ServerPlayer player, String namespace) {
		float yaw = Mth.wrapDegrees(player.getYRot() + 180.0F);
		CommandSourceStack source = player.createCommandSourceStack()
			.withAnchor(EntityAnchorArgument.Anchor.FEET)
			.withRotation(new Vec2(0.0F, yaw))
			.withMaximumPermission(LevelBasedPermissionSet.OWNER)
			.withSuppressedOutput();
		String rootSelector = "@e[type=minecraft:block_display,tag=" + namespace + "_root,limit=1,sort=nearest]";
		String command = "tp " + rootSelector + " ^ ^ ^ " + yaw + " 0";
		player.level().getServer().getCommands().performPrefixedCommand(source, command);
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
