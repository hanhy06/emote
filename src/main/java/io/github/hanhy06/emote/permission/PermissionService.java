package io.github.hanhy06.emote.permission;

import net.fabricmc.fabric.api.permission.v1.PermissionPredicates;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

import java.util.function.Predicate;

public final class PermissionService {
    public static final String MANAGE_PERMISSION = "emote.manage";
    public static final String BYPASS_PERMISSION = "emote.bypass";
    public static final Predicate<CommandSourceStack> CAN_MANAGE = PermissionPredicates.require(permissionId(MANAGE_PERMISSION), PermissionLevel.GAMEMASTERS);

    private PermissionService() {}

    public static boolean has(ServerPlayer player, String permission, boolean defaultValue) {
        return player.checkPermission(permissionId(permission), defaultValue);
    }

    private static Identifier permissionId(String permission) {
        int separator = permission.indexOf('.');
        if (separator <= 0 || separator == permission.length() - 1) {
            throw new IllegalArgumentException("permission must use namespace.node format: " + permission);
        }
        return Identifier.fromNamespaceAndPath(permission.substring(0, separator), permission.substring(separator + 1));
    }

}
