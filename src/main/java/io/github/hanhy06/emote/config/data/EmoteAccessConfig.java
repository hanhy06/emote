package io.github.hanhy06.emote.config.data;

import java.util.*;

public record EmoteAccessConfig(List<String> disabled, Map<String, List<String>> permissions) {
    public EmoteAccessConfig {
        List<String> copiedDisabled = List.copyOf(Objects.requireNonNull(disabled, "disabled"));
        if (copiedDisabled.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("disabled emote id must not be blank");
        }
        disabled = copiedDisabled.stream().map(String::trim).distinct().toList();

        LinkedHashMap<String, List<String>> copiedPermissions = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : Objects.requireNonNull(permissions, "permissions").entrySet()) {
            String permission = entry.getKey();
            if (permission == null || permission.isBlank()) {
                throw new IllegalArgumentException("permission must not be blank");
            }
            List<String> ids = List.copyOf(Objects.requireNonNull(entry.getValue(), "permission emote ids"));
            if (ids.stream().anyMatch(id -> id == null || id.isBlank())) {
                throw new IllegalArgumentException("permission emote id must not be blank");
            }
            copiedPermissions.put(permission.trim(), ids.stream().map(String::trim).distinct().toList());
        }
        permissions = Collections.unmodifiableMap(copiedPermissions);
    }

    public static EmoteAccessConfig createDefault() {
        return new EmoteAccessConfig(List.of(), Map.of("emote.default", List.of("*")));
    }

    public boolean isEnabled(String id) {
        return !disabled.contains(id);
    }
}
