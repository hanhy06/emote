package io.github.hanhy06.emote.config;

import io.github.hanhy06.emote.config.data.PackConfig;

public interface PackConfigListener {
    void onPackConfigReload(PackConfig newPackConfig);
}
