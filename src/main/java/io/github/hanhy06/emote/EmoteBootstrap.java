package io.github.hanhy06.emote;

import io.github.hanhy06.emote.animation.AnimationDirectoryLoader;
import io.github.hanhy06.emote.animation.AnimationServerPreparer;
import io.github.hanhy06.emote.application.ApiEventDispatcher;
import io.github.hanhy06.emote.application.EmoteApiImpl;
import io.github.hanhy06.emote.application.EmotePlayService;
import io.github.hanhy06.emote.application.EmoteQueryService;
import io.github.hanhy06.emote.command.AdminCommand;
import io.github.hanhy06.emote.command.CommandRegistrar;
import io.github.hanhy06.emote.command.EmoteMenu;
import io.github.hanhy06.emote.command.UserCommand;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.content.EmoteCatalog;
import io.github.hanhy06.emote.network.PayloadRegistry;
import io.github.hanhy06.emote.network.PlaybackStateService;
import io.github.hanhy06.emote.network.WheelSyncService;
import io.github.hanhy06.emote.permission.PermissionService;
import io.github.hanhy06.emote.playback.PlaybackEngine;
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
        PlayerSkinManager skins = new PlayerSkinManager();
        PlaybackEngine playback = new PlaybackEngine(skins);
        PlaybackStateService playbackState = new PlaybackStateService();
        ApiEventDispatcher apiEvents = new ApiEventDispatcher();
        EmoteQueryService queries = new EmoteQueryService(catalog, permissions);
        EmotePlayService play = new EmotePlayService(catalog, permissions, playback, apiEvents);
        IdlePlaybackService idlePlayback = new IdlePlaybackService(permissions, play, playback);
        WheelSyncService wheelSync = new WheelSyncService(queries);
        ReloadService reload = new ReloadService(
            configManager,
            catalog,
            new AnimationDirectoryLoader(),
            playback,
            wheelSync
        );

        new EmoteApiImpl(
            catalog,
            play,
            playback,
            apiEvents,
            wheelSync::syncAll,
            new AnimationServerPreparer()
        );
        CommandRegistrar commands = new CommandRegistrar(
            new UserCommand(playback, new EmoteMenu(configManager, catalog, queries, playback), queries, play),
            new AdminCommand(catalog, playback, permissions, reload, configManager)
        );
        ServerLifecycle lifecycle = new ServerLifecycle(skins, play, catalog, playback, reload, wheelSync, idlePlayback);

        configManager.addAccessConfigListener(permissions);
        configManager.addAccessConfigListener(idlePlayback);
        configManager.addListener(skins);
        configManager.addListener(playback);
        playback.addStateListener(playbackState);
        playback.addStateListener(apiEvents);
        playback.registerVisibilityService();
        PayloadRegistry.register();
        lifecycle.register();
        commands.register();

        Emote.LOGGER.info("{} ready", Emote.MOD_ID);
    }
}
