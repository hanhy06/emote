package io.github.hanhy06.emote.permission;

import net.fabricmc.fabric.api.permission.v1.PermissionPredicates;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

import java.util.function.Predicate;

public final class PermissionService {
    public static final Identifier MANAGE_PERMISSION = Identifier.fromNamespaceAndPath("emote", "manage");
    public static final Identifier BYPASS_PERMISSION = Identifier.fromNamespaceAndPath("emote", "bypass");
    public static final Predicate<CommandSourceStack> CAN_MANAGE = PermissionPredicates.require(MANAGE_PERMISSION, PermissionLevel.GAMEMASTERS);

    private PermissionService() {}

    public static boolean has(ServerPlayer player, Identifier permission, boolean defaultValue) {
        return player.checkPermission(permission, defaultValue);
    }
}
