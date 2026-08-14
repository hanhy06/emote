package io.github.hanhy06.emote.server;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.animation.AnimationDirectoryLoader;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.content.EmoteCatalog;
import io.github.hanhy06.emote.content.EmoteSequence;
import io.github.hanhy06.emote.content.PreparedEmote;
import io.github.hanhy06.emote.content.PreparedSequence;
import io.github.hanhy06.emote.network.WheelSyncService;
import io.github.hanhy06.emote.playback.PlaybackEngine;

public final class ReloadService {
    private final ConfigManager configManager;
    private final EmoteCatalog emoteCatalog;
    private final DirectoryContentsLoader directoryLoader;
    private final PlaybackEngine playbackEngine;
    private final WheelSyncService wheelSyncService;

    public ReloadService(
        ConfigManager configManager,
        EmoteCatalog emoteCatalog,
        AnimationDirectoryLoader directoryLoader,
        PlaybackEngine playbackEngine,
        WheelSyncService wheelSyncService
    ) {
        this(configManager, emoteCatalog, directoryLoader::load, playbackEngine, wheelSyncService);
    }

    ReloadService(
        ConfigManager configManager,
        EmoteCatalog emoteCatalog,
        DirectoryContentsLoader directoryLoader,
        PlaybackEngine playbackEngine,
        WheelSyncService wheelSyncService
    ) {
        this.configManager = configManager;
        this.emoteCatalog = emoteCatalog;
        this.directoryLoader = directoryLoader;
        this.playbackEngine = playbackEngine;
        this.wheelSyncService = wheelSyncService;
    }

    public void loadOnServerStart() {
        this.configManager.configure();
        this.configManager.readConfig();
        this.configManager.readAccessConfig();
        int emoteCount = reloadRegistry();
        Emote.LOGGER.info("emotes={}", emoteCount);
    }

    public ReloadResult reloadFromCommand() {
        boolean configLoaded = this.configManager.readConfig();
        boolean emoteAccessConfigLoaded = this.configManager.readAccessConfig();
        return new ReloadResult(configLoaded, emoteAccessConfigLoaded, reloadLoadedConfig());
    }

    private int reloadLoadedConfig() {
        this.playbackEngine.stopAll(PlaybackStopReason.RELOAD);
        int emoteCount = reloadRegistry();
        this.wheelSyncService.syncAll();
        Emote.LOGGER.info("reload emotes={}", emoteCount);
        return emoteCount;
    }

    private int reloadRegistry() {
        var contents = this.directoryLoader.load(this.configManager.getAnimationDirectory());
        var emotes = contents.animations().stream()
            .map(PreparedEmote::from)
            .toList();
        var animationsById = emotes.stream().collect(java.util.stream.Collectors.toMap(
            PreparedEmote::id,
            java.util.function.Function.identity()
        ));
        var sequences = contents.sequences().stream()
            .map(sequence -> resolveSequence(sequence, animationsById))
            .filter(java.util.Objects::nonNull)
            .toList();
        int ignoredCount = this.emoteCatalog.replace(emotes, sequences);
        if (ignoredCount > 0) {
            Emote.LOGGER.warn(
                "Ignoring {} enabled file emotes because of API id conflicts or the registry limit of {}",
                ignoredCount,
                EmoteCatalog.MAX_EMOTE_COUNT
            );
        }
        return this.emoteCatalog.size();
    }

    private PreparedSequence resolveSequence(
        EmoteSequence sequence,
        java.util.Map<String, PreparedEmote> animationsById
    ) {
        try {
            return PreparedSequence.resolve(sequence, animationsById);
        } catch (IllegalArgumentException exception) {
            Emote.LOGGER.warn("Ignoring invalid emote sequence {}: {}", sequence.sourcePath(), exception.getMessage());
            return null;
        }
    }

    @FunctionalInterface
    interface DirectoryContentsLoader {
        AnimationDirectoryLoader.DirectoryContents load(java.nio.file.Path directory);
    }
}
