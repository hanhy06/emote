package io.github.hanhy06.emote.server;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.animation.AnimationDirectoryLoader;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.emote.EmoteRegistry;
import io.github.hanhy06.emote.emote.EmoteSequence;
import io.github.hanhy06.emote.emote.RegisteredEmote;
import io.github.hanhy06.emote.emote.RegisteredSequence;
import io.github.hanhy06.emote.network.WheelSyncService;
import io.github.hanhy06.emote.playback.PlaybackManager;

public final class ReloadService {
    private final ConfigManager configManager;
    private final EmoteRegistry emoteRegistry;
    private final DirectoryContentsLoader directoryLoader;
    private final PlaybackManager playbackManager;
    private final WheelSyncService wheelSyncService;

    public ReloadService(
        ConfigManager configManager,
        EmoteRegistry emoteRegistry,
        AnimationDirectoryLoader directoryLoader,
        PlaybackManager playbackManager,
        WheelSyncService wheelSyncService
    ) {
        this(configManager, emoteRegistry, directoryLoader::load, playbackManager, wheelSyncService);
    }

    ReloadService(
        ConfigManager configManager,
        EmoteRegistry emoteRegistry,
        DirectoryContentsLoader directoryLoader,
        PlaybackManager playbackManager,
        WheelSyncService wheelSyncService
    ) {
        this.configManager = configManager;
        this.emoteRegistry = emoteRegistry;
        this.directoryLoader = directoryLoader;
        this.playbackManager = playbackManager;
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
        this.playbackManager.stopAll(PlaybackStopReason.RELOAD);
        int emoteCount = reloadRegistry();
        this.wheelSyncService.syncAll();
        Emote.LOGGER.info("reload emotes={}", emoteCount);
        return emoteCount;
    }

    private int reloadRegistry() {
        var contents = this.directoryLoader.load(this.configManager.getAnimationDirectory());
        var emotes = contents.animations().stream()
            .map(RegisteredEmote::from)
            .toList();
        var animationsById = emotes.stream().collect(java.util.stream.Collectors.toMap(
            RegisteredEmote::id,
            java.util.function.Function.identity()
        ));
        var sequences = contents.sequences().stream()
            .map(sequence -> resolveSequence(sequence, animationsById))
            .filter(java.util.Objects::nonNull)
            .toList();
        int ignoredCount = this.emoteRegistry.replace(emotes, sequences);
        if (ignoredCount > 0) {
            Emote.LOGGER.warn(
                "Ignoring {} enabled file emotes because of API id conflicts or the registry limit of {}",
                ignoredCount,
                EmoteRegistry.MAX_EMOTE_COUNT
            );
        }
        return this.emoteRegistry.size();
    }

    private RegisteredSequence resolveSequence(
        EmoteSequence sequence,
        java.util.Map<String, RegisteredEmote> animationsById
    ) {
        try {
            return RegisteredSequence.resolve(sequence, animationsById);
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
