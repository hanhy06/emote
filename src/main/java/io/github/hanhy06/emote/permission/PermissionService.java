package io.github.hanhy06.emote.permission;

import io.github.hanhy06.emote.config.EmoteAccessConfigListener;
import io.github.hanhy06.emote.config.data.EmoteAccessConfig;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

public class PermissionService implements EmoteAccessConfigListener {
    private static final String DEFAULT_PERMISSION = "emote.default";
    private static final String ALL_IDS = "*";
    private final PermissionChecker permissionChecker;
    private Map<String, List<String>> permissionIds = Map.of();

    public PermissionService() {
        this(Permissions::check);
    }

    PermissionService(PermissionChecker permissionChecker) {
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permission checker");
    }

    @Override
    public void onEmoteAccessConfigReload(EmoteAccessConfig newConfig) {
        this.permissionIds = newConfig.permissions();
    }

    public boolean canReload(CommandSourceStack source) {
        return source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_ADMIN);
    }

    public boolean canManageEmotes(CommandSourceStack source) {
        return source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
    }

    public boolean canPlay(ServerPlayer player, String id) {
        for (Map.Entry<String, List<String>> entry : this.permissionIds.entrySet()) {
            String permission = entry.getKey();
            boolean grantedByDefault = permission.equals(DEFAULT_PERMISSION);
            List<String> ids = entry.getValue();
            if (ids != null && (ids.contains(ALL_IDS) || ids.contains(id))
                && this.permissionChecker.test(player, permission, grantedByDefault)) {
                return true;
            }
        }
        return false;
    }

    public Predicate<CommandSourceStack> requireReload() {
        return this::canReload;
    }

    public Predicate<CommandSourceStack> requireGameMaster() {
        return this::canManageEmotes;
    }

    @FunctionalInterface
    interface PermissionChecker {
        boolean test(ServerPlayer player, String permission, boolean defaultValue);
    }
}
