package io.github.hanhy06.emote.permission;

import io.github.hanhy06.emote.config.PackConfigListener;
import io.github.hanhy06.emote.config.data.PackConfig;
import io.github.hanhy06.emote.config.data.PackOverride;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

public class PermissionService implements PackConfigListener {
    private static final PermissionLevel DEFAULT_PACK_PERMISSION_LEVEL = PermissionLevel.ALL;
    private Map<String, String> namespacePermissionMap = Map.of();

    @Override
    public void onPackConfigReload(PackConfig newPackConfig) {
        LinkedHashMap<String, String> nextNamespacePermissionMap = new LinkedHashMap<>();
        for (Map.Entry<String, PackOverride> entry : newPackConfig.packs().entrySet()) {
            nextNamespacePermissionMap.put(entry.getKey(), normalizePermission(entry.getValue().permission()));
        }

        this.namespacePermissionMap = Map.copyOf(nextNamespacePermissionMap);
    }

    public boolean canReload(CommandSourceStack source) {
        return source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_ADMIN);
    }

    public boolean canManageEmotes(CommandSourceStack source) {
        return source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
    }

    public boolean canPlay(ServerPlayer player, String namespace) {
        return hasPermission(player, findNamespacePermission(namespace));
    }

    public Predicate<CommandSourceStack> requireReload() {
        return this::canReload;
    }

    public Predicate<CommandSourceStack> requireGameMaster() {
        return this::canManageEmotes;
    }

    String findNamespacePermission(String namespace) {
        if (this.namespacePermissionMap.containsKey(namespace)) {
            return normalizePermission(this.namespacePermissionMap.get(namespace));
        }

        return "";
    }

    private boolean hasPermission(ServerPlayer player, String permission) {
        if (permission == null || permission.isBlank()) {
            return true;
        }

        return Permissions.check(player, permission, DEFAULT_PACK_PERMISSION_LEVEL);
    }

    private String normalizePermission(String permission) {
        return permission == null ? "" : permission.trim();
    }
}
