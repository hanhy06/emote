package io.github.hanhy06.emote.server;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.bdengine.BDEngineDatapackProcessor;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.emote.EmoteRegistry;
import io.github.hanhy06.emote.network.service.WheelSyncService;
import io.github.hanhy06.emote.playback.PlaybackManager;

public final class EmoteReloadService {
    private final ReloadOperations operations;
    private boolean skipNextConfigReload;

    public EmoteReloadService(
        ConfigManager configManager,
        EmoteRegistry emoteRegistry,
        BDEngineDatapackProcessor datapackProcessor,
        PlaybackManager playbackManager,
        WheelSyncService wheelSyncService
    ) {
        this(new ReloadOperations() {
            @Override
            public boolean readConfig() {
                return configManager.readConfig();
            }

            @Override
            public boolean readPackConfig() {
                return configManager.readPackConfig();
            }

            @Override
            public boolean enableDatapacks() {
                return datapackProcessor.enableEmoteDatapacks();
            }

            @Override
            public int reloadEmotes() {
                return datapackProcessor.reloadServerEmotes();
            }

            @Override
            public int emoteCount() {
                return emoteRegistry.size();
            }

            @Override
            public void stopAllEmotes() {
                playbackManager.stopAllEmotes();
            }

            @Override
            public void syncAllPlayers() {
                wheelSyncService.syncAll();
            }
        });
    }

    EmoteReloadService(ReloadOperations operations) {
        this.operations = operations;
    }

    public void handleServerStarted() {
        if (!enableDatapacksWithPreparedConfig()) {
            int emoteCount = reloadEmotesAndSync(false);
            Emote.LOGGER.info("emotes={}", emoteCount);
        }
    }

    public void handleDataPackReloadStart() {
        if (this.skipNextConfigReload) {
            this.skipNextConfigReload = false;
            return;
        }

        this.operations.readConfig();
        this.operations.readPackConfig();
    }

    public void handleDataPackReloadEnd(boolean success) {
        if (!success) {
            Emote.LOGGER.warn("Datapack reload failed");
            return;
        }

        int emoteCount = reloadEmotesAndSync(true);
        Emote.LOGGER.info("reload emotes={}", emoteCount);
    }

    public EmoteReloadResult reloadFromCommand() {
        boolean configLoaded = this.operations.readConfig();
        boolean packConfigLoaded = this.operations.readPackConfig();
        boolean resourceReload = enableDatapacksWithPreparedConfig();
        int emoteCount = resourceReload
            ? this.operations.emoteCount()
            : reloadEmotesAndSync(false);
        return new EmoteReloadResult(configLoaded, packConfigLoaded, emoteCount, resourceReload);
    }

    private boolean enableDatapacksWithPreparedConfig() {
        this.skipNextConfigReload = true;
        boolean resourceReload = this.operations.enableDatapacks();
        if (!resourceReload) {
            this.skipNextConfigReload = false;
        }
        return resourceReload;
    }

    private int reloadEmotesAndSync(boolean stopActiveEmotes) {
        if (stopActiveEmotes) {
            this.operations.stopAllEmotes();
        }
        int emoteCount = this.operations.reloadEmotes();
        this.operations.syncAllPlayers();
        return emoteCount;
    }

    interface ReloadOperations {
        boolean readConfig();

        boolean readPackConfig();

        boolean enableDatapacks();

        int reloadEmotes();

        int emoteCount();

        void stopAllEmotes();

        void syncAllPlayers();
    }
}
