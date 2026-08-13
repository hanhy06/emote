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

    public record IdleSettings(int delaySeconds, List<Choice> choices) {
        public IdleSettings {
            if (delaySeconds < 1) {
                throw new IllegalArgumentException("idle delay_seconds must be at least 1");
            }
            choices = List.copyOf(choices);
            if (choices.isEmpty()) {
                throw new IllegalArgumentException("idle emote must not be empty");
            }
            if (choices.stream().anyMatch(Objects::isNull)) {
                throw new NullPointerException("idle emote choices");
            }
            if (choices.stream().map(Choice::id).distinct().count() != choices.size()) {
                throw new IllegalArgumentException("idle emote must not contain duplicate ids");
            }
            boolean weighted = choices.getFirst().chance() > 0;
            if (choices.stream().anyMatch(choice -> (choice.chance() > 0) != weighted)) {
                throw new IllegalArgumentException("idle emote must use either equal or explicit chances");
            }
            if (weighted && choices.stream().mapToInt(Choice::chance).sum() != 100) {
                throw new IllegalArgumentException("idle emote chances must total 100");
            }
        }

        public IdleSettings(int delaySeconds, Collection<String> emotes) {
            this(delaySeconds, normalizeIds(
                List.copyOf(emotes),
                "idle emote",
                "idle emote id must not be blank",
                true
            ).stream().map(id -> new Choice(id, 0)).toList());
        }

        public List<String> emote() {
            return this.choices.stream().map(Choice::id).toList();
        }

        public record Choice(String id, int chance) {
            public Choice {
                if (id == null || id.isBlank()) {
                    throw new IllegalArgumentException("idle emote id must not be blank");
                }
                id = id.trim();
                if (chance < 0 || chance > 100) {
                    throw new IllegalArgumentException("idle emote chance must be between 1 and 100");
                }
            }
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
