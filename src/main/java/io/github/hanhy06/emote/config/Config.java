package io.github.hanhy06.emote.config;

import java.util.Objects;

public record Config(
    int schemaVersion,
    int menuPageSize,
    String mineSkinApiKey,
    int mineSkinPollIntervalSeconds,
    int mineSkinCacheRetentionDays,
    int mineSkinCacheMaxMiB,
    int maxActiveDisplayEntities
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int DEFAULT_MINESKIN_CACHE_RETENTION_DAYS = 30;
    public static final int DEFAULT_MINESKIN_CACHE_MAX_MIB = 256;
    public static final int DEFAULT_MAX_ACTIVE_DISPLAY_ENTITIES = 512;

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
        if (mineSkinCacheRetentionDays < 1 || mineSkinCacheRetentionDays > 3_650) {
            throw new IllegalArgumentException("mineskin_cache_retention_days must be between 1 and 3650");
        }
        if (mineSkinCacheMaxMiB < 1 || mineSkinCacheMaxMiB > 1_048_576) {
            throw new IllegalArgumentException("mineskin_cache_max_mib must be between 1 and 1048576");
        }
        if (maxActiveDisplayEntities < 0 || maxActiveDisplayEntities > 1_048_576) {
            throw new IllegalArgumentException("max_active_display_entities must be between 0 and 1048576");
        }
        Objects.requireNonNull(mineSkinApiKey, "mineSkinApiKey");
    }

    public static Config createDefault() {
        return new Config(
            CURRENT_SCHEMA_VERSION,
            6,
            "",
            3,
            DEFAULT_MINESKIN_CACHE_RETENTION_DAYS,
            DEFAULT_MINESKIN_CACHE_MAX_MIB,
            DEFAULT_MAX_ACTIVE_DISPLAY_ENTITIES
        );
    }
}
