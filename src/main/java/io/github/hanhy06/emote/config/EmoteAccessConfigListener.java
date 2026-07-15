package io.github.hanhy06.emote.config;

import io.github.hanhy06.emote.config.data.EmoteAccessConfig;

public interface EmoteAccessConfigListener {
    void onEmoteAccessConfigReload(EmoteAccessConfig newConfig);
}
