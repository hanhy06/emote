package io.github.hanhy06.emote.config;

import java.util.*;

public record AccessConfig(List<String> disabled, List<PermissionEntry> permissions) {
    public AccessConfig {
        disabled = normalizeIds(disabled, "disabled", "disabled emote id must not be blank", false);

        Objects.requireNonNull(permissions, "permissions");
        Set<String> seenPermissions = new HashSet<>();
        for (PermissionEntry entry : permissions) {
            Objects.requireNonNull(entry, "permission entry");
            if (!seenPermissions.add(entry.permission())) {
                throw new IllegalArgumentException("duplicate permission: " + entry.permission());
            }
        }
        permissions = List.copyOf(permissions);
    }

    public static AccessConfig createDefault() {
        return new AccessConfig(
            List.of(),
            List.of(new PermissionEntry(
                "emote.default",
                List.of("*"),
                Optional.of(new IdleSettings(300, List.of("drink:default")))
            ))
        );
    }

    public boolean isEnabled(String id) {
        return !disabled.contains(id);
    }

    public record PermissionEntry(String permission, List<String> emotes, Optional<IdleSettings> idle) {
        public PermissionEntry {
            if (permission == null || permission.isBlank()) {
                throw new IllegalArgumentException("permission must not be blank");
            }
            permission = permission.trim();

            emotes = normalizeIds(
                emotes,
                "permission emotes",
                "permission emote id must not be blank",
                false
            );
            Objects.requireNonNull(idle, "idle");
        }
    }

    public record IdleSettings(int delaySeconds, List<String> emote) {
        public IdleSettings {
            if (delaySeconds < 1) {
                throw new IllegalArgumentException("idle delay_seconds must be at least 1");
            }
            emote = normalizeIds(emote, "idle emote", "idle emote id must not be blank", true);
        }
    }

    private static List<String> normalizeIds(
        List<String> ids,
        String fieldName,
        String invalidIdMessage,
        boolean requireNonEmpty
    ) {
        Objects.requireNonNull(ids, fieldName);
        if (requireNonEmpty && ids.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be empty");
        }
        return ids.stream()
            .map(id -> {
                if (id == null || id.isBlank()) {
                    throw new IllegalArgumentException(invalidIdMessage);
                }
                return id.trim();
            })
            .distinct()
            .toList();
    }
}
