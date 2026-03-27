package io.github.hanhy06.emot.config;

import io.github.hanhy06.emot.Emote;
import io.github.hanhy06.emot.permission.EmotePermission;
import net.fabricmc.loader.api.FabricLoader;

import java.util.LinkedHashMap;

public record Config(
	String version,
	int menu_page_size,
	boolean quick_action_enabled,
	String emote_permission,
	LinkedHashMap<String, String> emote_permissions
) {
	public static Config createDefault() {
		return new Config(
			FabricLoader.getInstance()
				.getModContainer(Emote.MOD_ID)
				.orElseThrow()
				.getMetadata()
				.getVersion()
				.getFriendlyString(),
			6,
			true,
			EmotePermission.DEFAULT_EMOTE_PERMISSION,
			new LinkedHashMap<>()
		);
	}
}
