package io.github.hanhy06.emote.config.data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record PackConfig(Map<String, PackOverride> packs, Map<String, List<String>> permissions) {
    public PackConfig {
        LinkedHashMap<String, PackOverride> copiedPacks = new LinkedHashMap<>(Objects.requireNonNull(packs, "packs"));
        for (Map.Entry<String, PackOverride> entry : copiedPacks.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException("pack namespace must not be blank");
            }
            Objects.requireNonNull(entry.getValue(), "pack override");
        }
        packs = Collections.unmodifiableMap(copiedPacks);

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
        return new PackConfig(Map.of(), Map.of("default", List.of()));
    }

    public PackOverride findOverride(String namespace) {
        return packs.get(namespace);
    }

    public boolean isEnabled(String namespace) {
        PackOverride packOverride = findOverride(namespace);
        return packOverride == null || packOverride.enabled();
    }
}
