package io.github.hanhy06.emote.permission;

import io.github.hanhy06.emote.config.PackConfigListener;
import io.github.hanhy06.emote.config.data.PackConfig;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

public class PermissionService implements PackConfigListener {
    private static final String DEFAULT_PERMISSION = "default";
    private static final String ALL_NAMESPACES = "*";
    private final BiPredicate<ServerPlayer, String> permissionChecker;
    private Map<String, List<String>> permissionNamespaces = Map.of();

    public PermissionService() {
        this(Permissions::check);
    }

    PermissionService(BiPredicate<ServerPlayer, String> permissionChecker) {
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permission checker");
    }

    @Override
    public void onPackConfigReload(PackConfig newPackConfig) {
        this.permissionNamespaces = newPackConfig.permissions();
    }

    public boolean canReload(CommandSourceStack source) {
        return source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_ADMIN);
    }

    public boolean canManageEmotes(CommandSourceStack source) {
        return source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
    }

    public boolean canPlay(ServerPlayer player, String namespace) {
        if (includesNamespace(this.permissionNamespaces.get(DEFAULT_PERMISSION), namespace)) {
            return true;
        }

        for (Map.Entry<String, List<String>> entry : this.permissionNamespaces.entrySet()) {
            if (!entry.getKey().equals(DEFAULT_PERMISSION)
                && includesNamespace(entry.getValue(), namespace)
                && this.permissionChecker.test(player, entry.getKey())) {
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

    boolean isDefaultAllowed(String namespace) {
        return includesNamespace(this.permissionNamespaces.get(DEFAULT_PERMISSION), namespace);
    }

    List<String> findPermissions(String namespace) {
        return this.permissionNamespaces.entrySet().stream()
            .filter(entry -> !entry.getKey().equals(DEFAULT_PERMISSION))
            .filter(entry -> includesNamespace(entry.getValue(), namespace))
            .map(Map.Entry::getKey)
            .toList();
    }

    private boolean includesNamespace(List<String> namespaces, String namespace) {
        return namespaces != null && (namespaces.contains(ALL_NAMESPACES) || namespaces.contains(namespace));
    }
}
