package io.github.hanhy06.emote.permission;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

import java.util.Objects;
import java.util.function.Predicate;

public class PermissionService {
    public static final String MANAGE_PERMISSION = "emote.manage";
    public static final String BYPASS_PERMISSION = "emote.bypass";

    private final PermissionChecker permissionChecker;

    public PermissionService() {
        this(Permissions::check);
    }

    PermissionService(PermissionChecker permissionChecker) {
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permission checker");
    }

    public boolean canManage(CommandSourceStack source) {
        return Permissions.check(source, MANAGE_PERMISSION, PermissionLevel.GAMEMASTERS);
    }

    public boolean has(ServerPlayer player, String permission, boolean defaultValue) {
        return this.permissionChecker.test(player, permission, defaultValue);
    }

    public Predicate<CommandSourceStack> requireManage() {
        return this::canManage;
    }

    @FunctionalInterface
    interface PermissionChecker {
        boolean test(ServerPlayer player, String permission, boolean defaultValue);
    }
}
