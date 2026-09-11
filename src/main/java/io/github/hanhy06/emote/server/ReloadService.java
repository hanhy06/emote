package io.github.hanhy06.emote.server;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.content.*;
import io.github.hanhy06.emote.content.loader.EmoteDirectoryLoader;
import io.github.hanhy06.emote.network.WheelSyncService;
import io.github.hanhy06.emote.playback.PlaybackEngine;
import io.github.hanhy06.emote.resource.PolymerResourcePackDistributor;

import java.io.UncheckedIOException;
import java.util.function.Supplier;

public final class ReloadService {
    private final ConfigManager configManager;
    private final EmoteCatalog emoteCatalog;
    private final LoadResultLoader directoryLoader;
    private final PlaybackStopper playbackStopper;
    private final Runnable wheelSynchronizer;
    private final Supplier<PolymerResourcePackDistributor.BuildResult> resourcePackBuilder;
    private final Runnable resourcePackPusher;

    public ReloadService(
        ConfigManager configManager,
        EmoteCatalog emoteCatalog,
        EmoteDirectoryLoader directoryLoader,
        PlaybackEngine playbackEngine,
        WheelSyncService wheelSyncService,
        Supplier<PolymerResourcePackDistributor.BuildResult> resourcePackBuilder,
        Runnable resourcePackPusher
    ) {
        this(
            configManager,
            emoteCatalog,
            directoryLoader::load,
            playbackEngine::stopAll,
            wheelSyncService::syncAll,
            resourcePackBuilder,
            resourcePackPusher
        );
    }

    ReloadService(
        ConfigManager configManager,
        EmoteCatalog emoteCatalog,
        LoadResultLoader directoryLoader,
        PlaybackStopper playbackStopper,
        Runnable wheelSynchronizer,
        Supplier<PolymerResourcePackDistributor.BuildResult> resourcePackBuilder,
        Runnable resourcePackPusher
    ) {
        this.configManager = configManager;
        this.emoteCatalog = emoteCatalog;
        this.directoryLoader = directoryLoader;
        this.playbackStopper = playbackStopper;
        this.wheelSynchronizer = wheelSynchronizer;
        this.resourcePackBuilder = resourcePackBuilder;
        this.resourcePackPusher = resourcePackPusher;
    }

    public ReloadResult reload() {
        ConfigManager.PreparedConfig preparedConfig = this.configManager.prepare();
        if (preparedConfig == null) {
            EmoteMod.LOGGER.warn("Emote reload failed; keeping the current state because the configuration is invalid");
            return result(ReloadStats.failed(this.emoteCatalog.fileEmotes().size(), ReloadResult.Failure.CONFIG_LOAD));
        }

        ReloadStats stats = reloadPreparedConfig(preparedConfig);
        return result(stats);
    }

    private ReloadResult result(ReloadStats stats) {
        var accessConfig = this.configManager.getAccessConfig();
        return new ReloadResult(
            accessConfig.disabled().size(),
            accessConfig.permissions().size(),
            stats.detectedFileCount(),
            stats.loadedEmoteCount(),
            stats.failure()
        );
    }

    private ReloadStats reloadPreparedConfig(ConfigManager.PreparedConfig preparedConfig) {
        PreparedRegistry prepared;
        try {
            prepared = prepareRegistry();
        } catch (UncheckedIOException exception) {
            EmoteMod.LOGGER.warn("Emote reload failed; keeping the current registry and active playbacks");
            return ReloadStats.failed(this.emoteCatalog.fileEmotes().size(), ReloadResult.Failure.EMOTE_LOAD);
        }
        PolymerResourcePackDistributor.BuildResult resourcePackResult = this.resourcePackBuilder.get();
        if (resourcePackResult == PolymerResourcePackDistributor.BuildResult.FAILED) {
            EmoteMod.LOGGER.warn("Emote reload failed because the resource pack could not be built; keeping the current state");
            return ReloadStats.failed(this.emoteCatalog.fileEmotes().size(), ReloadResult.Failure.RESOURCE_PACK_BUILD);
        }

        this.configManager.apply(preparedConfig);
        ReloadStats stats = replaceRegistry(prepared);
        this.playbackStopper.stopAll(PlaybackStopReason.RELOAD);
        if (resourcePackResult == PolymerResourcePackDistributor.BuildResult.BUILT) {
            this.resourcePackPusher.run();
        }
        this.wheelSynchronizer.run();
        EmoteMod.LOGGER.info("Loaded {} emotes from {} files", stats.loadedEmoteCount(), stats.detectedFileCount());
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
        return new ReloadStats(prepared.detectedFileCount(), this.emoteCatalog.fileEmotes().size(), ReloadResult.Failure.NONE);
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

    private record ReloadStats(int detectedFileCount, int loadedEmoteCount, ReloadResult.Failure failure) {
        private static ReloadStats failed(int retainedEmoteCount, ReloadResult.Failure failure) {
            return new ReloadStats(0, retainedEmoteCount, failure);
        }
    }

    private record PreparedRegistry(int detectedFileCount, java.util.List<PlayableEmote> definitions) {
        private PreparedRegistry {
            definitions = java.util.List.copyOf(definitions);
        }
    }
}
