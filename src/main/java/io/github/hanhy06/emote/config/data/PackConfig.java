package io.github.hanhy06.emote.config.data;

import java.util.LinkedHashMap;

public record PackConfig(LinkedHashMap<String, PackOverride> packs) {
    public static PackConfig createDefault() {
        return new PackConfig(new LinkedHashMap<>());
    }

    public PackOverride findOverride(String namespace) {
        return packs.get(namespace);
    }

    public boolean isEnabled(String namespace) {
        PackOverride packOverride = findOverride(namespace);
        return packOverride == null || packOverride.enabled();
    }
}
