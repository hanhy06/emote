package io.github.hanhy06.emote.server;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.content.*;
import io.github.hanhy06.emote.content.loader.EmoteDirectoryLoader;
import io.github.hanhy06.emote.network.WheelSyncService;
import io.github.hanhy06.emote.playback.PlaybackEngine;
import io.github.hanhy06.emote.resource.ResourcePackService;

public final class ReloadService {
    private final ConfigManager configManager;
    private final EmoteCatalog emoteCatalog;
    private final LoadResultLoader directoryLoader;
    private final PlaybackEngine playbackEngine;
    private final WheelSyncService wheelSyncService;
    private final Runnable resourcePackReloader;

    public ReloadService(
        ConfigManager configManager,
        EmoteCatalog emoteCatalog,
        EmoteDirectoryLoader directoryLoader,
        PlaybackEngine playbackEngine,
        WheelSyncService wheelSyncService
    ) {
        this(
            configManager,
            emoteCatalog,
            directoryLoader::load,
            playbackEngine,
            wheelSyncService,
            new ResourcePackService(configManager)::rebuild
        );
    }

    ReloadService(
        ConfigManager configManager,
        EmoteCatalog emoteCatalog,
        LoadResultLoader directoryLoader,
        PlaybackEngine playbackEngine,
        WheelSyncService wheelSyncService
    ) {
        this(configManager, emoteCatalog, directoryLoader, playbackEngine, wheelSyncService, () -> {});
    }

    ReloadService(
        ConfigManager configManager,
        EmoteCatalog emoteCatalog,
        LoadResultLoader directoryLoader,
        PlaybackEngine playbackEngine,
        WheelSyncService wheelSyncService,
        Runnable resourcePackReloader
    ) {
        this.configManager = configManager;
        this.emoteCatalog = emoteCatalog;
        this.directoryLoader = directoryLoader;
        this.playbackEngine = playbackEngine;
        this.wheelSyncService = wheelSyncService;
        this.resourcePackReloader = resourcePackReloader;
    }

    public void loadOnServerStart() {
        this.configManager.configure();
        this.configManager.readConfig();
        this.configManager.readAccessConfig();
        this.resourcePackReloader.run();
        ReloadStats stats = reloadRegistry();
        EmoteMod.LOGGER.info("emote files detected={} loaded={}", stats.detectedFileCount(), stats.loadedEmoteCount());
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
        this.resourcePackReloader.run();
        ReloadStats stats = reloadRegistry();
        this.wheelSyncService.syncAll();
        EmoteMod.LOGGER.info("reload emote files detected={} loaded={}", stats.detectedFileCount(), stats.loadedEmoteCount());
        return stats;
    }

    private ReloadStats reloadRegistry() {
        var contents = this.directoryLoader.load(this.configManager.getEmoteDirectory());
        var emotes = contents.animations().stream()
            .map(this::prepareAnimation)
            .filter(java.util.Objects::nonNull)
            .toList();
        var animationsById = emotes.stream().collect(java.util.stream.Collectors.toMap(
            PreparedAnimation::id,
            java.util.function.Function.identity()
        ));
        var sequences = contents.sequences().stream()
            .map(sequence -> resolveSequence(sequence, animationsById))
            .filter(java.util.Objects::nonNull)
            .toList();
        java.util.List<PlayableEmote> definitions = new java.util.ArrayList<>(emotes);
        definitions.addAll(sequences);
        int ignoredCount = this.emoteCatalog.replace(definitions);
        if (ignoredCount > 0) {
            EmoteMod.LOGGER.warn(
                "Ignoring {} enabled file emotes because of API id conflicts or the registry limit of {}",
                ignoredCount,
                EmoteCatalog.MAX_EMOTE_COUNT
            );
        }
        return new ReloadStats(contents.detectedFileCount(), this.emoteCatalog.fileEmotes().size());
    }

    private PreparedAnimation prepareAnimation(LoadedAnimation animation) {
        try {
            return PreparedAnimation.from(animation);
        } catch (IllegalArgumentException exception) {
            EmoteMod.LOGGER.warn("Ignoring invalid emote animation {}: {}", animation.sourcePath(), exception.getMessage());
            return null;
        }
    }

    private PreparedSequence resolveSequence(
        EmoteSequence sequence,
        java.util.Map<String, PreparedAnimation> animationsById
    ) {
        try {
            return PreparedSequence.resolve(sequence, animationsById);
        } catch (IllegalArgumentException exception) {
            EmoteMod.LOGGER.warn("Ignoring invalid emote sequence {}: {}", sequence.sourcePath(), exception.getMessage());
            return null;
        }
    }

    @FunctionalInterface
    interface LoadResultLoader {
        EmoteDirectoryLoader.LoadResult load(java.nio.file.Path directory);
    }

    private record ReloadStats(int detectedFileCount, int loadedEmoteCount) {
    }
}
