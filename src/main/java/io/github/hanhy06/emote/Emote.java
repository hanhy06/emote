package io.github.hanhy06.emote;

import io.github.hanhy06.emote.animation.AnimationDirectoryLoader;
import io.github.hanhy06.emote.animation.AnimationServerPreparer;
import io.github.hanhy06.emote.application.ApiEventDispatcher;
import io.github.hanhy06.emote.application.EmoteApiImpl;
import io.github.hanhy06.emote.command.AdminCommand;
import io.github.hanhy06.emote.command.CommandRegistrar;
import io.github.hanhy06.emote.command.EmoteMenu;
import io.github.hanhy06.emote.command.UserCommand;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.content.EmoteCatalog;
import io.github.hanhy06.emote.application.EmotePlayService;
import io.github.hanhy06.emote.application.EmoteQueryService;
import io.github.hanhy06.emote.network.PayloadRegistry;
import io.github.hanhy06.emote.network.PlaybackStateService;
import io.github.hanhy06.emote.network.WheelSyncService;
import io.github.hanhy06.emote.permission.PermissionService;
import io.github.hanhy06.emote.playback.PlaybackEngine;
import io.github.hanhy06.emote.server.IdlePlaybackService;
import io.github.hanhy06.emote.server.ReloadService;
import io.github.hanhy06.emote.server.ServerLifecycle;
import io.github.hanhy06.emote.skin.PlayerSkinManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Emote implements ModInitializer {
    public static final String MOD_ID = "emote";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static MinecraftServer SERVER;

    @Override
    public void onInitialize() {
        ConfigManager configManager = new ConfigManager(FabricLoader.getInstance().getConfigDir());
        EmoteCatalog emoteRegistry = new EmoteCatalog();
        PermissionService permissionService = new PermissionService();

        PlayerSkinManager playerSkinManager = new PlayerSkinManager();
        PlaybackEngine playbackEngine = new PlaybackEngine(playerSkinManager);
        PlaybackStateService playbackStateService = new PlaybackStateService();
        ApiEventDispatcher apiEvents = new ApiEventDispatcher();

        EmoteQueryService playableEmoteService = new EmoteQueryService(emoteRegistry, permissionService);
        EmotePlayService playService = new EmotePlayService(
            emoteRegistry,
            permissionService,
            playbackEngine,
            apiEvents
        );
        IdlePlaybackService idlePlaybackService = new IdlePlaybackService(permissionService, playService, playbackEngine);

        EmoteMenu emoteMenu = new EmoteMenu(
            configManager,
            emoteRegistry,
            playableEmoteService,
            playbackEngine
        );
        WheelSyncService wheelSyncService = new WheelSyncService(playableEmoteService);

        new EmoteApiImpl(
            emoteRegistry,
            playService,
            playbackEngine,
            apiEvents,
            wheelSyncService,
            new AnimationServerPreparer()
        );
        ReloadService reloadService = new ReloadService(
            configManager,
            emoteRegistry,
            new AnimationDirectoryLoader(),
            playbackEngine,
            wheelSyncService
        );

        ServerLifecycle serverLifecycle = new ServerLifecycle(
            playerSkinManager,
            emoteRegistry,
            playbackEngine,
            reloadService,
            wheelSyncService,
            idlePlaybackService
        );
        UserCommand userCommand = new UserCommand(
            playbackEngine,
            emoteMenu,
            playableEmoteService,
            playService
        );
        AdminCommand adminCommand = new AdminCommand(
            emoteRegistry,
            playbackEngine,
            permissionService,
            reloadService,
            configManager
        );
        CommandRegistrar commandRegistrar = new CommandRegistrar(userCommand, adminCommand);

        configManager.addAccessConfigListener(permissionService);
        configManager.addAccessConfigListener(idlePlaybackService);
        configManager.addListener(playerSkinManager);
        configManager.addListener(playbackEngine);

        playbackEngine.addStateListener(playbackStateService);
        playbackEngine.addStateListener(apiEvents);
        playbackEngine.registerVisibilityService();

        PayloadRegistry.register();
        serverLifecycle.register();
        commandRegistrar.register();

        LOGGER.info("{} ready", MOD_ID);
    }
}
