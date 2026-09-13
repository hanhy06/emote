package io.github.hanhy06.emote.permission;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

import java.util.Objects;
import java.util.function.Predicate;

public final class PermissionService {
    public static final String MANAGE_PERMISSION = "emote.manage";
    public static final String BYPASS_PERMISSION = "emote.bypass";

    private final PermissionBackend permissionBackend;

    public PermissionService() {
        this(new FabricPermissionBackend());
    }

    public PermissionService(PermissionBackend permissionBackend) {
        this.permissionBackend = Objects.requireNonNull(permissionBackend, "permission backend");
    }

    public boolean canManage(CommandSourceStack source) {
        return this.permissionBackend.has(source, MANAGE_PERMISSION, PermissionLevel.GAMEMASTERS);
    }

    public boolean has(ServerPlayer player, String permission, boolean defaultValue) {
        return this.permissionBackend.has(player, permission, defaultValue);
    }

    public Predicate<CommandSourceStack> requireManage() {
        return this::canManage;
    }

    public interface PermissionBackend {
        boolean has(CommandSourceStack source, String permission, PermissionLevel defaultLevel);

        boolean has(ServerPlayer player, String permission, boolean defaultValue);
    }

    private static final class FabricPermissionBackend implements PermissionBackend {
        @Override
        public boolean has(CommandSourceStack source, String permission, PermissionLevel defaultLevel) {
            return Permissions.check(source, permission, defaultLevel);
        }

        @Override
        public boolean has(ServerPlayer player, String permission, boolean defaultValue) {
            return Permissions.check(player, permission, defaultValue);
        }
    }
}
