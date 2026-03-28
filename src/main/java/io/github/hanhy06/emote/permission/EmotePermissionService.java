package io.github.hanhy06.emote.permission;

import io.github.hanhy06.emote.config.Config;
import io.github.hanhy06.emote.config.ConfigListener;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.function.Predicate;

public class EmotePermissionService implements ConfigListener {
	private Config config = Config.createDefault();

	@Override
	public void onConfigReload(Config newConfig) {
		this.config = newConfig;
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

		return hasPermission(player, findDatapackPermission(namespace), 2);
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

	private String findDatapackPermission(String namespace) {
		Map<String, String> emotePermissionMap = this.config.emote_permissions();
		if (emotePermissionMap.containsKey(namespace)) {
			return normalizePermission(emotePermissionMap.get(namespace));
		}

		return "";
	}

	private boolean hasBasePermission(ServerPlayer player) {
		return hasPermission(player, this.config.emote_permission(), 2);
	}

	private boolean hasPermission(ServerPlayer player, String permission, int fallbackLevel) {
		if (permission == null || permission.isBlank()) {
			return true;
		}

		return Permissions.check(player, permission, fallbackLevel);
	}

	private String normalizePermission(String permission) {
		return permission == null ? "" : permission.trim();
	}
}
