package io.github.hanhy06.emote.resource;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.config.ConfigManager;

import java.io.IOException;

public final class ResourcePackService {
    private final ConfigManager configManager;
    private final ResourcePackAssembler assembler;

    public ResourcePackService(ConfigManager configManager) {
        this(configManager, new ResourcePackAssembler());
    }

    ResourcePackService(ConfigManager configManager, ResourcePackAssembler assembler) {
        this.configManager = configManager;
        this.assembler = assembler;
    }

    public void rebuild() {
        try {
            ResourcePackAssembler.BuildResult result = this.assembler.assemble(
                this.configManager.getResourcePackDirectory(),
                this.configManager.getGeneratedResourcePackPath()
            );
            EmoteMod.LOGGER.info(
                "Built emote resource pack: resources={} size={} sha1={}",
                result.resourceCount(),
                result.archiveSize(),
                result.sha1()
            );
        } catch (IOException exception) {
            EmoteMod.LOGGER.warn("Failed to build emote resource pack: {}", exception.getMessage());
        }
    }
}
