package io.github.hanhy06.emote.server;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.network.service.WheelSyncService;
import io.github.hanhy06.emote.playback.PlaybackManager;
import net.minecraft.server.MinecraftServer;

public final class EmoteReloadService {
    private final ConfigManager configManager;
    private final EmoteAnimationService animationService;
    private final PlaybackManager playbackManager;
    private final WheelSyncService wheelSyncService;

    public EmoteReloadService(
        ConfigManager configManager,
        EmoteAnimationService animationService,
        PlaybackManager playbackManager,
        WheelSyncService wheelSyncService
    ) {
        this.configManager = configManager;
        this.animationService = animationService;
        this.playbackManager = playbackManager;
        this.wheelSyncService = wheelSyncService;
    }

    public void handleServerStarted() {
        MinecraftServer server = Emote.SERVER;
        if (server == null) {
            return;
        }
        int emoteCount = this.animationService.reload(server);
        this.wheelSyncService.syncAll();
        Emote.LOGGER.info("emotes={}", emoteCount);
    }

    public void handleDataPackReloadStart() {
        this.configManager.readConfig();
        this.configManager.readPackConfig();
    }

    public void handleDataPackReloadEnd(boolean success) {
        if (!success) {
            Emote.LOGGER.warn("Datapack reload failed; JSON emotes were not reloaded");
            return;
        }
        reloadLoadedConfig();
    }

    public EmoteReloadResult reloadFromCommand() {
        boolean configLoaded = this.configManager.readConfig();
        boolean packConfigLoaded = this.configManager.readPackConfig();
        return new EmoteReloadResult(configLoaded, packConfigLoaded, reloadLoadedConfig());
    }

    private int reloadLoadedConfig() {
        MinecraftServer server = Emote.SERVER;
        if (server == null) {
            return 0;
        }
        this.playbackManager.stopAllEmotes();
        int emoteCount = this.animationService.reload(server);
        this.wheelSyncService.syncAll();
        Emote.LOGGER.info("reload emotes={}", emoteCount);
        return emoteCount;
    }
}
