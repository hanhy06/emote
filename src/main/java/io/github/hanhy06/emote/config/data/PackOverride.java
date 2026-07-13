package io.github.hanhy06.emote.config.data;

import java.util.Objects;

public record PackOverride(boolean enabled, String permission) {
    public PackOverride {
        Objects.requireNonNull(permission, "permission");
        permission = permission.trim();
    }

    public static PackOverride createDefault() {
        return new PackOverride(true, "");
    }
}
