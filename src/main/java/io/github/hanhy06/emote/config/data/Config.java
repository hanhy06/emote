package io.github.hanhy06.emote.config.data;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.permission.PermissionNode;
import net.fabricmc.loader.api.FabricLoader;

public record Config(
	String version,
	int menu_page_size,
	int player_skin_port,
	String mineskin_api_key,
	String emote_permission
) {
	public static Config createDefault() {
		return new Config(
			FabricLoader.getInstance()
				.getModContainer(Emote.MOD_ID)
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("dev"),
			6,
			0,
			"",
			PermissionNode.DEFAULT_EMOTE_PERMISSION
		);
	}
}
