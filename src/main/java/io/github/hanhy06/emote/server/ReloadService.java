package io.github.hanhy06.emote.server;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.content.*;
import io.github.hanhy06.emote.content.loader.EmoteDirectoryLoader;
import io.github.hanhy06.emote.network.WheelSyncService;
import io.github.hanhy06.emote.playback.PlaybackEngine;

public final class ReloadService {
    private final ConfigManager configManager;
    private final EmoteCatalog emoteCatalog;
    private final LoadResultLoader directoryLoader;
    private final PlaybackStopper playbackStopper;
    private final Runnable wheelSynchronizer;
    private final Runnable resourcePackReloader;

    public ReloadService(
        ConfigManager configManager,
        EmoteCatalog emoteCatalog,
        EmoteDirectoryLoader directoryLoader,
        PlaybackEngine playbackEngine,
        WheelSyncService wheelSyncService,
        Runnable resourcePackReloader
    ) {
        this(
            configManager,
            emoteCatalog,
            directoryLoader::load,
            playbackEngine::stopAll,
            wheelSyncService::syncAll,
            resourcePackReloader
        );
    }

    ReloadService(
        ConfigManager configManager,
        EmoteCatalog emoteCatalog,
        LoadResultLoader directoryLoader,
        PlaybackStopper playbackStopper,
        Runnable wheelSynchronizer,
        Runnable resourcePackReloader
    ) {
        this.configManager = configManager;
        this.emoteCatalog = emoteCatalog;
        this.directoryLoader = directoryLoader;
        this.playbackStopper = playbackStopper;
        this.wheelSynchronizer = wheelSynchronizer;
        this.resourcePackReloader = resourcePackReloader;
    }

    public void loadOnServerStart() {
        this.configManager.initialize();
        ReloadStats stats = replaceRegistry(prepareRegistry());
        EmoteMod.LOGGER.info("Loaded {} emotes from {} files", stats.loadedEmoteCount(), stats.detectedFileCount());
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
        PreparedRegistry prepared = prepareRegistry();
        ReloadStats stats = replaceRegistry(prepared);
        this.playbackStopper.stopAll(PlaybackStopReason.RELOAD);
        this.resourcePackReloader.run();
        this.wheelSynchronizer.run();
        EmoteMod.LOGGER.info("Reloaded {} emotes from {} files", stats.loadedEmoteCount(), stats.detectedFileCount());
        return stats;
    }

    private PreparedRegistry prepareRegistry() {
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
        return new PreparedRegistry(contents.detectedFileCount(), definitions);
    }

    private ReloadStats replaceRegistry(PreparedRegistry prepared) {
        int ignoredCount = this.emoteCatalog.replace(prepared.definitions());
        if (ignoredCount > 0) {
            EmoteMod.LOGGER.warn(
                "Ignoring {} enabled file emotes because of API ID conflicts or the {}-emote registry limit",
                ignoredCount,
                EmoteCatalog.MAX_EMOTE_COUNT
            );
        }
        return new ReloadStats(prepared.detectedFileCount(), this.emoteCatalog.fileEmotes().size());
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

    @FunctionalInterface
    interface PlaybackStopper {
        void stopAll(PlaybackStopReason reason);
    }

    private record ReloadStats(int detectedFileCount, int loadedEmoteCount) {
    }

    private record PreparedRegistry(int detectedFileCount, java.util.List<PlayableEmote> definitions) {
        private PreparedRegistry {
            definitions = java.util.List.copyOf(definitions);
        }
    }
}
