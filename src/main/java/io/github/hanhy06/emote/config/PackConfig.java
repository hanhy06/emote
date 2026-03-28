package io.github.hanhy06.emote.config;

import io.github.hanhy06.emote.Emote;
import net.fabricmc.loader.api.FabricLoader;

import java.util.LinkedHashMap;
import java.util.List;

public record PackConfig(
	String version,
	LinkedHashMap<String, List<EmotePack>> permissions
) {
	public static PackConfig createDefault() {
		return new PackConfig(
			FabricLoader.getInstance()
				.getModContainer(Emote.MOD_ID)
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("dev"),
			new LinkedHashMap<>()
		);
	}
}
