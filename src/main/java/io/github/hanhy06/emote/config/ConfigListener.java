package io.github.hanhy06.emote.config;

import io.github.hanhy06.emote.config.data.Config;

public interface ConfigListener {
	void onConfigReload(Config newConfig);
}
