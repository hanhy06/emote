package io.github.hanhy06.emote.permission;

import io.github.hanhy06.emote.config.ConfigListener;
import io.github.hanhy06.emote.config.IdentifierConfigListener;
import io.github.hanhy06.emote.config.data.Config;
import io.github.hanhy06.emote.config.data.IdentifierConfig;
import io.github.hanhy06.emote.config.data.IdentifierEntry;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.entity.Entity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

public class PermissionService implements ConfigListener, IdentifierConfigListener {
	private static final PermissionLevel DEFAULT_EMOTE_PERMISSION_LEVEL = PermissionLevel.GAMEMASTERS;
	private Config config = Config.createDefault();
	private Map<String, String> namespacePermissionMap = Map.of();

	@Override
	public void onConfigReload(Config newConfig) {
		this.config = newConfig;
	}

	@Override
	public void onIdentifierConfigReload(IdentifierConfig newIdentifierConfig) {
		LinkedHashMap<String, String> nextNamespacePermissionMap = new LinkedHashMap<>();
		for (Map.Entry<String, java.util.List<IdentifierEntry>> entry : newIdentifierConfig.permissions().entrySet()) {
			String permission = normalizePermission(entry.getKey());
			for (IdentifierEntry identifierEntry : entry.getValue()) {
				nextNamespacePermissionMap.put(identifierEntry.datapack_identifier(), permission);
			}
		}

		this.namespacePermissionMap = Map.copyOf(nextNamespacePermissionMap);
	}

	public boolean canOpenDialog(ServerPlayer player) {
		return hasBasePermission(player);
	}

	public boolean canList(CommandSourceStack source) {
		ServerPlayer player = findPlayer(source);
		return player != null && hasBasePermission(player);
	}

	public boolean canReload(CommandSourceStack source) {
		return source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_ADMIN);
	}

	public boolean canStop(ServerPlayer player) {
		return hasBasePermission(player);
	}

	public boolean canPlay(ServerPlayer player, String namespace, String animationName) {
		if (!hasBasePermission(player)) {
			return false;
		}

		return hasPermission(player, findNamespacePermission(namespace), DEFAULT_EMOTE_PERMISSION_LEVEL);
	}

	public Predicate<CommandSourceStack> requireDialogOpen() {
		return source -> {
			ServerPlayer player = findPlayer(source);
			return player != null && canOpenDialog(player);
		};
	}

	public Predicate<CommandSourceStack> requireList() {
		return source -> canList(source);
	}

	public Predicate<CommandSourceStack> requireReload() {
		return source -> canReload(source);
	}

	public Predicate<CommandSourceStack> requirePlay() {
		return source -> {
			ServerPlayer player = findPlayer(source);
			return player != null && hasBasePermission(player);
		};
	}

	public Predicate<CommandSourceStack> requireStop() {
		return source -> {
			ServerPlayer player = findPlayer(source);
			return player != null && canStop(player);
		};
	}

	private ServerPlayer findPlayer(CommandSourceStack source) {
		Entity entity = source.getEntity();
		return entity instanceof ServerPlayer player ? player : null;
	}

	String findNamespacePermission(String namespace) {
		if (this.namespacePermissionMap.containsKey(namespace)) {
			return normalizePermission(this.namespacePermissionMap.get(namespace));
		}

		return "";
	}

	private boolean hasBasePermission(ServerPlayer player) {
		return hasPermission(player, this.config.emote_permission(), DEFAULT_EMOTE_PERMISSION_LEVEL);
	}

	private boolean hasPermission(ServerPlayer player, String permission, PermissionLevel fallbackLevel) {
		if (permission == null || permission.isBlank()) {
			return true;
		}

		return Permissions.check(player, permission, fallbackLevel);
	}

	private String normalizePermission(String permission) {
		return permission == null ? "" : permission.trim();
	}
}
