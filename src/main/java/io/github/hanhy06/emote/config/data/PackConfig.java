package io.github.hanhy06.emote.config.data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record PackConfig(Map<String, PackOverride> packs) {
    public PackConfig {
        LinkedHashMap<String, PackOverride> copiedPacks = new LinkedHashMap<>(Objects.requireNonNull(packs, "packs"));
        for (Map.Entry<String, PackOverride> entry : copiedPacks.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException("pack namespace must not be blank");
            }
            Objects.requireNonNull(entry.getValue(), "pack override");
        }
        packs = Collections.unmodifiableMap(copiedPacks);
    }

    public static PackConfig createDefault() {
        return new PackConfig(Map.of());
    }

    public PackOverride findOverride(String namespace) {
        return packs.get(namespace);
    }

    public boolean isEnabled(String namespace) {
        PackOverride packOverride = findOverride(namespace);
        return packOverride == null || packOverride.enabled();
    }
}
