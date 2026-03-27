package io.github.hanhy06.emot.permission;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Predicate;

public class EmotePermissionService {
	public boolean canOpenDialog(ServerPlayer player) {
		return Permissions.check(player, EmotePermission.DIALOG_OPEN, 2);
	}

	public boolean canList(CommandSourceStack source) {
		return Permissions.check(source, EmotePermission.COMMAND_LIST, 2);
	}

	public boolean canReload(CommandSourceStack source) {
		return Permissions.check(source, EmotePermission.COMMAND_RELOAD, 4);
	}

	public boolean canStop(ServerPlayer player) {
		return Permissions.check(player, EmotePermission.STOP, 2);
	}

	public boolean canPlay(ServerPlayer player, String namespace, String animationName) {
		return Permissions.check(player, EmotePermission.PLAY, 2)
			&& Permissions.check(player, EmotePermission.createPlayPermission(namespace, animationName), 2);
	}

	public Predicate<CommandSourceStack> requireDialogOpen() {
		return source -> {
			ServerPlayer player = findPlayer(source);
			return player != null && canOpenDialog(player);
		};
	}

	public Predicate<CommandSourceStack> requireList() {
		return Permissions.require(EmotePermission.COMMAND_LIST, 2);
	}

	public Predicate<CommandSourceStack> requireReload() {
		return Permissions.require(EmotePermission.COMMAND_RELOAD, 4);
	}

	public Predicate<CommandSourceStack> requirePlay() {
		return source -> {
			ServerPlayer player = findPlayer(source);
			return player != null && Permissions.check(player, EmotePermission.PLAY, 2);
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
}
