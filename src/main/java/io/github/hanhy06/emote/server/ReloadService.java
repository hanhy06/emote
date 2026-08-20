package io.github.hanhy06.emote.server;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.animation.AnimationDirectoryLoader;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.content.*;
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
        ReloadStats stats = reloadRegistry();
        Emote.LOGGER.info("emote files detected={} loaded={}", stats.detectedFileCount(), stats.loadedEmoteCount());
    }

    public ReloadResult reloadFromCommand() {
        this.configManager.readConfig();
        this.configManager.readAccessConfig();
        ReloadStats stats = reloadLoadedConfig();
        var accessConfig = this.configManager.getAccessConfig();
        return new ReloadResult(
            accessConfig.disabled().size(),
            accessConfig.permissions().size(),
            stats.detectedFileCount(),
            stats.loadedEmoteCount()
        );
    }

    private ReloadStats reloadLoadedConfig() {
        this.playbackEngine.stopAll(PlaybackStopReason.RELOAD);
        ReloadStats stats = reloadRegistry();
        this.wheelSyncService.syncAll();
        Emote.LOGGER.info("reload emote files detected={} loaded={}", stats.detectedFileCount(), stats.loadedEmoteCount());
        return stats;
    }

    private ReloadStats reloadRegistry() {
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
        java.util.List<PreparedDefinition> definitions = new java.util.ArrayList<>(emotes);
        definitions.addAll(sequences);
        int ignoredCount = this.emoteCatalog.replace(definitions);
        if (ignoredCount > 0) {
            Emote.LOGGER.warn(
                "Ignoring {} enabled file emotes because of API id conflicts or the registry limit of {}",
                ignoredCount,
                EmoteCatalog.MAX_EMOTE_COUNT
            );
        }
        return new ReloadStats(contents.detectedFileCount(), this.emoteCatalog.getFileDefinitions().size());
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

    private record ReloadStats(int detectedFileCount, int loadedEmoteCount) {
    }
}
