package io.github.hanhy06.emote.server;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.animation.EmoteAnimationDirectoryLoader;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.emote.EmoteRegistry;
import io.github.hanhy06.emote.emote.RegisteredEmote;
import io.github.hanhy06.emote.network.WheelSyncService;
import io.github.hanhy06.emote.playback.PlaybackManager;
import net.minecraft.server.MinecraftServer;

public final class EmoteReloadService {
    private final ConfigManager configManager;
    private final EmoteRegistry emoteRegistry;
    private final EmoteAnimationDirectoryLoader directoryLoader;
    private final PlaybackManager playbackManager;
    private final WheelSyncService wheelSyncService;

    public EmoteReloadService(
        ConfigManager configManager,
        EmoteRegistry emoteRegistry,
        EmoteAnimationDirectoryLoader directoryLoader,
        PlaybackManager playbackManager,
        WheelSyncService wheelSyncService
    ) {
        this.configManager = configManager;
        this.emoteRegistry = emoteRegistry;
        this.directoryLoader = directoryLoader;
        this.playbackManager = playbackManager;
        this.wheelSyncService = wheelSyncService;
    }

    public void loadOnServerStart(MinecraftServer server) {
        this.configManager.configure();
        this.configManager.readConfig();
        this.configManager.readEmoteAccessConfig();
        int emoteCount = reloadRegistry(server);
        Emote.LOGGER.info("emotes={}", emoteCount);
    }

    public EmoteReloadResult reloadFromCommand(MinecraftServer server) {
        boolean configLoaded = this.configManager.readConfig();
        boolean emoteAccessConfigLoaded = this.configManager.readEmoteAccessConfig();
        return new EmoteReloadResult(configLoaded, emoteAccessConfigLoaded, reloadLoadedConfig(server));
    }

    private int reloadLoadedConfig(MinecraftServer server) {
        this.playbackManager.stopAllEmotes(PlaybackStopReason.RELOAD);
        int emoteCount = reloadRegistry(server);
        this.wheelSyncService.syncAll(server);
        Emote.LOGGER.info("reload emotes={}", emoteCount);
        return emoteCount;
    }

    private int reloadRegistry(MinecraftServer server) {
        var emoteAccessConfig = this.configManager.getEmoteAccessConfig();
        var emotes = this.directoryLoader.load(this.configManager.getAnimationDirectory(), server).stream()
            .map(RegisteredEmote::from)
            .filter(emote -> emoteAccessConfig.isEnabled(emote.id()))
            .toList();
        int ignoredCount = this.emoteRegistry.replace(emotes);
        if (ignoredCount > 0) {
            Emote.LOGGER.warn(
                "Ignoring {} enabled file emotes because of API id conflicts or the registry limit of {}",
                ignoredCount,
                EmoteRegistry.MAX_EMOTE_COUNT
            );
        }
        return this.emoteRegistry.size();
    }
}
