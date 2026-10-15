package io.github.hanhy06.emote.server;

import io.github.hanhy06.emote.content.LoadedSequence;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.content.*;
import io.github.hanhy06.emote.content.loader.EmoteDirectoryLoader;
import io.github.hanhy06.emote.network.WheelSyncService;
import io.github.hanhy06.emote.playback.PlaybackEngine;
import io.github.hanhy06.emote.resource.ResourcePackService;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ReloadService {
    private final ConfigManager configManager;
    private final EmoteCatalog emoteCatalog;
    private final EmoteDirectoryLoader directoryLoader;
    private final PlaybackEngine playbackEngine;
    private final WheelSyncService wheelSyncService;
    private final ResourcePackService resources;

    public ReloadService(
        ConfigManager configManager,
        EmoteCatalog emoteCatalog,
        EmoteDirectoryLoader directoryLoader,
        PlaybackEngine playbackEngine,
        WheelSyncService wheelSyncService,
        ResourcePackService resources
    ) {
        this.configManager = configManager;
        this.emoteCatalog = emoteCatalog;
        this.directoryLoader = directoryLoader;
        this.playbackEngine = playbackEngine;
        this.wheelSyncService = wheelSyncService;
        this.resources = resources;
    }

    public ReloadResult reload() {
        ConfigManager.PreparedConfig preparedConfig = this.configManager.prepare();
        if (preparedConfig == null) {
            EmoteMod.LOGGER.warn("Emote reload failed; keeping the current state because the configuration is invalid");
            return result(0, this.emoteCatalog.fileEmotes().size(), ReloadResult.Failure.CONFIG_LOAD);
        }

        PreparedRegistry prepared;
        try {
            prepared = prepareRegistry();
        } catch (UncheckedIOException exception) {
            EmoteMod.LOGGER.warn("Emote reload failed; keeping the current registry and active playbacks");
            return result(0, this.emoteCatalog.fileEmotes().size(), ReloadResult.Failure.EMOTE_LOAD);
        }
        ResourcePackService.BuildResult resourcePackResult = this.resources.rebuild();
        if (resourcePackResult == ResourcePackService.BuildResult.FAILED) {
            EmoteMod.LOGGER.warn("Emote reload failed because the resource pack could not be built; keeping the current state");
            return result(0, this.emoteCatalog.fileEmotes().size(), ReloadResult.Failure.RESOURCE_PACK_BUILD);
        }

        this.configManager.apply(preparedConfig);
        int loadedEmoteCount = replaceRegistry(prepared);
        this.playbackEngine.stopAll(PlaybackStopReason.RELOAD);
        if (resourcePackResult == ResourcePackService.BuildResult.BUILT) {
            this.resources.pushToOnlinePlayers();
        }
        this.wheelSyncService.syncAll();
        EmoteMod.LOGGER.info("Loaded {} emotes from {} files", loadedEmoteCount, prepared.detectedFileCount());
        this.resources.notifyOnlinePlayers();
        return result(prepared.detectedFileCount(), loadedEmoteCount, ReloadResult.Failure.NONE);
    }

    private ReloadResult result(int detectedFileCount, int loadedEmoteCount, ReloadResult.Failure failure) {
        var accessConfig = this.configManager.getAccessConfig();
        return new ReloadResult(
            accessConfig.disabled().size(),
            accessConfig.permissions().size(),
            detectedFileCount,
            loadedEmoteCount,
            failure
        );
    }

    private PreparedRegistry prepareRegistry() {
        var contents = this.directoryLoader.load(this.configManager.getEmoteDirectory());
        var animations = contents.animations().stream()
            .map(this::prepareAnimation)
            .filter(Objects::nonNull)
            .toList();
        var animationsById = animations.stream().collect(Collectors.toMap(
            PreparedAnimation::id,
            Function.identity()
        ));
        var sequences = contents.sequences().stream()
            .map(sequence -> prepareSequence(sequence, animationsById))
            .filter(Objects::nonNull)
            .toList();
        List<PreparedEmote> definitions = new ArrayList<>(animations);
        definitions.addAll(sequences);
        return new PreparedRegistry(contents.detectedFileCount(), definitions);
    }

    private int replaceRegistry(PreparedRegistry prepared) {
        int ignoredCount = this.emoteCatalog.replace(prepared.definitions());
        if (ignoredCount > 0) {
            EmoteMod.LOGGER.warn(
                "Ignoring {} enabled file emotes because of API ID conflicts or the {}-emote registry limit",
                ignoredCount,
                EmoteCatalog.MAX_EMOTE_COUNT
            );
        }
        return this.emoteCatalog.fileEmotes().size();
    }

    private PreparedAnimation prepareAnimation(LoadedAnimation animation) {
        try {
            return PreparedAnimation.prepare(animation, EmoteMod.SERVER.registryAccess());
        } catch (io.github.hanhy06.emote.api.EmoteLoadException exception) {
            EmoteMod.LOGGER.warn("Ignoring invalid emote animation {}: {}", animation.sourcePath(), exception.getMessage());
            return null;
        }
    }

    private PreparedSequence prepareSequence(
        LoadedSequence sequence,
        Map<String, PreparedAnimation> animationsById
    ) {
        try {
            return PreparedSequence.prepare(sequence, animationsById);
        } catch (io.github.hanhy06.emote.api.EmoteLoadException exception) {
            EmoteMod.LOGGER.warn("Ignoring invalid emote sequence {}: {}", sequence.sourcePath(), exception.getMessage());
            return null;
        }
    }

    private record PreparedRegistry(int detectedFileCount, List<PreparedEmote> definitions) {
        private PreparedRegistry {
            definitions = List.copyOf(definitions);
        }
    }
}
