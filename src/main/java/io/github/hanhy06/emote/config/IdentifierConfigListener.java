package io.github.hanhy06.emote.config;

import io.github.hanhy06.emote.config.data.IdentifierConfig;

public interface IdentifierConfigListener {
	void onIdentifierConfigReload(IdentifierConfig newIdentifierConfig);
}
