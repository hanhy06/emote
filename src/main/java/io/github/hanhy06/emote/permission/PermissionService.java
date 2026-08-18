package io.github.hanhy06.emote.permission;

import io.github.hanhy06.emote.config.AccessConfig;
import io.github.hanhy06.emote.config.AccessConfigListener;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

public class PermissionService implements AccessConfigListener {
    public static final String MANAGE_PERMISSION = "emote.manage";
    public static final String BYPASS_PERMISSION = "emote.bypass";
    private static final String DEFAULT_PERMISSION = "emote.default";
    private static final String ALL_IDS = "*";

    private final PermissionChecker permissionChecker;

    private List<AccessConfig.PermissionEntry> permissionEntries = List.of();
    private Set<String> disabled = Set.of();

    public PermissionService() {
        this(Permissions::check);
    }

    PermissionService(PermissionChecker permissionChecker) {
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permission checker");
    }

    @Override
    public void onAccessConfigReload(AccessConfig newConfig) {
        this.permissionEntries = newConfig.permissions();
        this.disabled = Set.copyOf(newConfig.disabled());
    }

    public boolean canManage(CommandSourceStack source) {
        return Permissions.check(source, MANAGE_PERMISSION, PermissionLevel.GAMEMASTERS);
    }

    public boolean canBypass(ServerPlayer player) {
        return this.permissionChecker.test(player, BYPASS_PERMISSION, false);
    }

    public boolean canPlay(ServerPlayer player, String id) {
        if (canBypass(player)) {
            return true;
        }
        if (isDisabled(id)) {
            return false;
        }
        for (AccessConfig.PermissionEntry entry : this.permissionEntries) {
            String permission = entry.permission();
            boolean grantedByDefault = permission.equals(DEFAULT_PERMISSION);
            List<String> ids = entry.emotes();
            if ((ids.contains(ALL_IDS) || ids.contains(id))
                && this.permissionChecker.test(player, permission, grantedByDefault)) {
                return true;
            }
        }
        return false;
    }

    public boolean isDisabled(String id) {
        return this.disabled.contains(id);
    }

    public Optional<AccessConfig.IdleSettings> findIdleSettings(ServerPlayer player) {
        for (AccessConfig.PermissionEntry entry : this.permissionEntries) {
            if (entry.idle().isEmpty()) {
                continue;
            }
            String permission = entry.permission();
            boolean grantedByDefault = permission.equals(DEFAULT_PERMISSION);
            if (this.permissionChecker.test(player, permission, grantedByDefault)) {
                return entry.idle();
            }
        }
        return Optional.empty();
    }

    public Predicate<CommandSourceStack> requireManage() {
        return this::canManage;
    }

    @FunctionalInterface
    interface PermissionChecker {
        boolean test(ServerPlayer player, String permission, boolean defaultValue);
    }
}
