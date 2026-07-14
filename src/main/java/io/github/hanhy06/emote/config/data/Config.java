package io.github.hanhy06.emote.config.data;

import io.github.hanhy06.emote.Emote;
import net.fabricmc.loader.api.FabricLoader;

public record Config(
    String version,
    int menu_page_size,
    String mineskin_api_key,
    int mineskin_poll_interval_seconds,
    String emote_permission
) {
    public static Config createDefault() {
        return new Config(
            FabricLoader.getInstance()
                .getModContainer(Emote.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("dev"),
            6,
            "",
            3,
            "emote.use"
        );
    }
}
