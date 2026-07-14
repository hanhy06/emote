package io.github.hanhy06.emote.config.data;

import java.util.Objects;

public record Config(
    int schemaVersion,
    int menuPageSize,
    String mineSkinApiKey,
    int mineSkinPollIntervalSeconds
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public Config {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported config schema_version: " + schemaVersion);
        }
        if (menuPageSize < 1) {
            throw new IllegalArgumentException("menu_page_size must be at least 1");
        }
        if (mineSkinPollIntervalSeconds < 1 || mineSkinPollIntervalSeconds > 60) {
            throw new IllegalArgumentException("mineskin_poll_interval_seconds must be between 1 and 60");
        }
        Objects.requireNonNull(mineSkinApiKey, "mineSkinApiKey");
    }

    public static Config createDefault() {
        return new Config(
            CURRENT_SCHEMA_VERSION,
            6,
            "",
            3
        );
    }
}
