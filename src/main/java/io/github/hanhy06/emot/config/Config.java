package io.github.hanhy06.emot.config;

import io.github.hanhy06.emot.Emote;
import io.github.hanhy06.emot.permission.EmotePermission;
import net.fabricmc.loader.api.FabricLoader;

import java.util.LinkedHashMap;

public record Config(
	String version,
	int menu_page_size,
	String dialog_permission,
	String play_permission,
	String stop_permission,
	String command_list_permission,
	String command_reload_permission,
	String default_emote_permission,
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
			EmotePermission.DEFAULT_DIALOG_PERMISSION,
			EmotePermission.DEFAULT_PLAY_PERMISSION,
			EmotePermission.DEFAULT_STOP_PERMISSION,
			EmotePermission.DEFAULT_COMMAND_LIST_PERMISSION,
			EmotePermission.DEFAULT_COMMAND_RELOAD_PERMISSION,
			EmotePermission.DEFAULT_EMOTE_PERMISSION,
			new LinkedHashMap<>()
		);
	}
}
