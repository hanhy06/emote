package io.github.hanhy06.emote.config.data;

import java.util.*;

public record PackConfig(List<String> disabled, Map<String, List<String>> permissions) {
    public PackConfig {
        List<String> copiedDisabled = List.copyOf(Objects.requireNonNull(disabled, "disabled"));
        if (copiedDisabled.stream().anyMatch(namespace -> namespace == null || namespace.isBlank())) {
            throw new IllegalArgumentException("disabled namespace must not be blank");
        }
        disabled = copiedDisabled.stream().map(String::trim).distinct().toList();

        LinkedHashMap<String, List<String>> copiedPermissions = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : Objects.requireNonNull(permissions, "permissions").entrySet()) {
            String permission = entry.getKey();
            if (permission == null || permission.isBlank()) {
                throw new IllegalArgumentException("permission must not be blank");
            }

            List<String> namespaces = List.copyOf(Objects.requireNonNull(entry.getValue(), "permission namespaces"));
            if (namespaces.stream().anyMatch(namespace -> namespace == null || namespace.isBlank())) {
                throw new IllegalArgumentException("permission namespace must not be blank");
            }
            copiedPermissions.put(permission.trim(), namespaces.stream().map(String::trim).distinct().toList());
        }
        permissions = Collections.unmodifiableMap(copiedPermissions);
    }

    public static PackConfig createDefault() {
        return new PackConfig(List.of(), Map.of("emote.default", List.of("*")));
    }

    public boolean isEnabled(String namespace) {
        return !disabled.contains(namespace);
    }
}
