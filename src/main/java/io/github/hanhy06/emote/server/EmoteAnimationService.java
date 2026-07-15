package io.github.hanhy06.emote.server;

import io.github.hanhy06.emote.animation.EmoteAnimationDirectoryLoader;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.emote.EmoteDefinition;
import io.github.hanhy06.emote.emote.EmoteRegistry;
import net.minecraft.server.MinecraftServer;

import java.util.Objects;

public final class EmoteAnimationService {
    private final ConfigManager configManager;
    private final EmoteRegistry emoteRegistry;
    private final EmoteAnimationDirectoryLoader directoryLoader;

    public EmoteAnimationService(
        ConfigManager configManager,
        EmoteRegistry emoteRegistry,
        EmoteAnimationDirectoryLoader directoryLoader
    ) {
        this.configManager = Objects.requireNonNull(configManager, "configManager");
        this.emoteRegistry = Objects.requireNonNull(emoteRegistry, "emoteRegistry");
        this.directoryLoader = Objects.requireNonNull(directoryLoader, "directoryLoader");
    }

    public int reload(MinecraftServer server) {
        var definitions = this.directoryLoader.load(this.configManager.getAnimationDirectory(), server).stream()
            .map(EmoteDefinition::create)
            .filter(definition -> this.configManager.getEmoteAccessConfig().isEnabled(definition.id()))
            .toList();
        this.emoteRegistry.replaceDefinitions(definitions);
        return definitions.size();
    }
}
