package io.github.hanhy06.emote;

import io.github.hanhy06.emote.application.*;
import io.github.hanhy06.emote.command.AdminCommand;
import io.github.hanhy06.emote.command.CommandRegistrar;
import io.github.hanhy06.emote.command.EmoteMenu;
import io.github.hanhy06.emote.command.UserCommand;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.content.EmoteCatalog;
import io.github.hanhy06.emote.content.loader.AnimationContentResolver;
import io.github.hanhy06.emote.content.loader.EmoteDirectoryLoader;
import io.github.hanhy06.emote.network.PayloadRegistry;
import io.github.hanhy06.emote.network.PlaybackStateSyncService;
import io.github.hanhy06.emote.network.WheelSyncService;
import io.github.hanhy06.emote.permission.PermissionService;
import io.github.hanhy06.emote.playback.PlaybackEngine;
import io.github.hanhy06.emote.playback.timeline.NamedCallbackDispatcher;
import io.github.hanhy06.emote.resource.PolymerResourcePackDistributor;
import io.github.hanhy06.emote.server.IdlePlaybackService;
import io.github.hanhy06.emote.server.ReloadService;
import io.github.hanhy06.emote.server.ServerLifecycle;
import io.github.hanhy06.emote.skin.PlayerSkinManager;
import net.fabricmc.loader.api.FabricLoader;

final class EmoteBootstrap {
    private EmoteBootstrap() {
    }

    static void initialize() {
        ConfigManager configManager = new ConfigManager(FabricLoader.getInstance().getConfigDir());
        EmoteCatalog catalog = new EmoteCatalog();
        PermissionService permissions = new PermissionService();
        PlaybackPolicyService playbackPolicy = new PlaybackPolicyService(permissions);
        PlayerSkinManager skins = new PlayerSkinManager();
        NamedCallbackDispatcher callbacks = new NamedCallbackDispatcher();
        PlaybackEngine playback = new PlaybackEngine(skins, callbacks);
        PlaybackStateSyncService playbackStateSync = new PlaybackStateSyncService();
        ApiEventDispatcher apiEvents = new ApiEventDispatcher();
        EmoteQueryService queries = new EmoteQueryService(catalog, playbackPolicy);
        EmotePlayService play = new EmotePlayService(catalog, playbackPolicy, playback, apiEvents);
        IdlePlaybackService idlePlayback = new IdlePlaybackService(playbackPolicy, play, playback);
        WheelSyncService wheelSync = new WheelSyncService(queries);
        PolymerResourcePackDistributor resourcePackDistributor = new PolymerResourcePackDistributor(configManager);
        ReloadService reload = new ReloadService(
            configManager,
            catalog,
            new EmoteDirectoryLoader(),
            playback,
            wheelSync,
            resourcePackDistributor::rebuildAndPush
        );

        new EmoteApiImpl(
            catalog,
            play,
            playback,
            apiEvents,
            callbacks,
            wheelSync::syncAll,
            new AnimationContentResolver()
        );
        CommandRegistrar commands = new CommandRegistrar(
            new UserCommand(playback, new EmoteMenu(configManager, catalog, queries, playback), queries, play),
            new AdminCommand(catalog, playback, permissions, reload, configManager)
        );
        ServerLifecycle lifecycle = new ServerLifecycle(skins, playbackPolicy, catalog, playback, reload, wheelSync, idlePlayback);

        configManager.addAccessConfigListener(playbackPolicy);
        configManager.addAccessConfigListener(idlePlayback);
        configManager.addListener(skins);
        configManager.addListener(playback);
        playback.addStateListener(playbackStateSync);
        playback.addStateListener(apiEvents);
        playback.registerVisibilityService();
        PayloadRegistry.register();
        lifecycle.register();
        commands.register();

        EmoteMod.LOGGER.info("Emote initialized");
    }
}
