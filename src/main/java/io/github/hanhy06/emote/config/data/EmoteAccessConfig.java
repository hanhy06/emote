package io.github.hanhy06.emote.config.data;

import java.util.*;

public record EmoteAccessConfig(List<String> disabled, List<PermissionEntry> permissions) {
    public EmoteAccessConfig {
        List<String> copiedDisabled = List.copyOf(Objects.requireNonNull(disabled, "disabled"));
        if (copiedDisabled.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("disabled emote id must not be blank");
        }
        disabled = copiedDisabled.stream().map(String::trim).distinct().toList();

        permissions = List.copyOf(Objects.requireNonNull(permissions, "permissions"));
        Set<String> seenPermissions = new HashSet<>();
        for (PermissionEntry entry : permissions) {
            Objects.requireNonNull(entry, "permission entry");
            if (!seenPermissions.add(entry.permission())) {
                throw new IllegalArgumentException("duplicate permission: " + entry.permission());
            }
        }
    }

    public static EmoteAccessConfig createDefault() {
        return new EmoteAccessConfig(
            List.of(),
            List.of(new PermissionEntry("emote.default", List.of("*"), Optional.empty()))
        );
    }

    public boolean isEnabled(String id) {
        return !disabled.contains(id);
    }

    public record PermissionEntry(String permission, List<String> emotes, Optional<IdleEmote> idle) {
        public PermissionEntry {
            if (permission == null || permission.isBlank()) {
                throw new IllegalArgumentException("permission must not be blank");
            }
            permission = permission.trim();

            List<String> copiedEmotes = List.copyOf(Objects.requireNonNull(emotes, "permission emotes"));
            if (copiedEmotes.stream().anyMatch(id -> id == null || id.isBlank())) {
                throw new IllegalArgumentException("permission emote id must not be blank");
            }
            emotes = copiedEmotes.stream().map(String::trim).distinct().toList();
            Objects.requireNonNull(idle, "idle");
        }
    }

    public record IdleEmote(int delaySeconds, List<String> emote) {
        public IdleEmote {
            if (delaySeconds < 1) {
                throw new IllegalArgumentException("idle delay_seconds must be at least 1");
            }
            List<String> copiedEmotes = List.copyOf(Objects.requireNonNull(emote, "idle emote"));
            if (copiedEmotes.isEmpty()) {
                throw new IllegalArgumentException("idle emote must not be empty");
            }
            if (copiedEmotes.stream().anyMatch(id -> id == null || id.isBlank())) {
                throw new IllegalArgumentException("idle emote id must not be blank");
            }
            emote = copiedEmotes.stream().map(String::trim).distinct().toList();
        }
    }
}
