package io.github.hanhy06.emote.permission;

import io.github.hanhy06.emote.config.ConfigListener;
import io.github.hanhy06.emote.config.PackConfigListener;
import io.github.hanhy06.emote.config.data.Config;
import io.github.hanhy06.emote.config.data.PackConfig;
import io.github.hanhy06.emote.config.data.PackOverride;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.entity.Entity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

public class PermissionService implements ConfigListener, PackConfigListener {
	private static final PermissionLevel DEFAULT_EMOTE_PERMISSION_LEVEL = PermissionLevel.ALL;
	private Config config = Config.createDefault();
	private Map<String, String> namespacePermissionMap = Map.of();

	@Override
	public void onConfigReload(Config newConfig) {
		this.config = newConfig;
	}

	@Override
	public void onPackConfigReload(PackConfig newPackConfig) {
		LinkedHashMap<String, String> nextNamespacePermissionMap = new LinkedHashMap<>();
		for (Map.Entry<String, PackOverride> entry : newPackConfig.packs().entrySet()) {
			nextNamespacePermissionMap.put(entry.getKey(), normalizePermission(entry.getValue().permission()));
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

	public boolean canPlay(ServerPlayer player, String namespace) {
		if (!hasBasePermission(player)) {
			return false;
		}

		return hasPermission(player, findNamespacePermission(namespace));
	}

	public Predicate<CommandSourceStack> requireReload() {
		return this::canReload;
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
		return hasPermission(player, this.config.emote_permission());
	}

	private boolean hasPermission(ServerPlayer player, String permission) {
		if (permission == null || permission.isBlank()) {
			return true;
		}

		return Permissions.check(player, permission, DEFAULT_EMOTE_PERMISSION_LEVEL);
	}

	private String normalizePermission(String permission) {
		return permission == null ? "" : permission.trim();
	}
}
